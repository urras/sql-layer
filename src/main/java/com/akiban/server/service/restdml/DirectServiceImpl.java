/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.server.service.restdml;

import static com.akiban.rest.resources.ResourceHelper.checkSchemaAccessible;
import static com.akiban.rest.resources.ResourceHelper.checkTableAccessible;
import static com.akiban.util.JsonUtils.createJsonGenerator;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;

import org.codehaus.jackson.JsonGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.CacheValueGenerator;
import com.akiban.ais.model.Parameter;
import com.akiban.ais.model.Routine;
import com.akiban.ais.model.Routine.CallingConvention;
import com.akiban.ais.model.Schema;
import com.akiban.ais.model.TableName;
import com.akiban.direct.Direct;
import com.akiban.rest.RestFunctionRegistrar;
import com.akiban.rest.RestServiceImpl;
import com.akiban.rest.resources.ResourceHelper;
import com.akiban.server.error.ExternalRoutineInvocationException;
import com.akiban.server.error.MalformedRequestException;
import com.akiban.server.error.NoSuchRoutineException;
import com.akiban.server.service.Service;
import com.akiban.server.service.dxl.DXLService;
import com.akiban.server.service.restdml.EndpointMetadata.EndpointAddress;
import com.akiban.server.service.restdml.EndpointMetadata.ParamCache;
import com.akiban.server.service.restdml.EndpointMetadata.ParamMetadata;
import com.akiban.server.service.restdml.EndpointMetadata.Tokenizer;
import com.akiban.server.service.routines.RoutineLoader;
import com.akiban.server.service.routines.ScriptInvoker;
import com.akiban.server.service.security.SecurityService;
import com.akiban.server.service.session.Session;
import com.akiban.server.service.session.SessionService;
import com.akiban.server.types3.Attribute;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TInstance;
import com.akiban.sql.embedded.EmbeddedJDBCService;
import com.akiban.sql.embedded.JDBCConnection;
import com.google.inject.Inject;
import com.persistit.exception.RollbackException;

public class DirectServiceImpl implements Service, DirectService {

    private static final Logger LOG = LoggerFactory.getLogger(DirectService.class.getName());

    private final static String TABLE_ARG_NAME = "table";
    private final static String MODULE_ARG_NAME = "module";
    private final static String SCHEMA_ARG_NAME = "schema";
    private final static String LANGUAGE = "language";
    private final static String FUNCTIONS = "functions";

    private final static String CALLING_CONVENTION = "calling_convention";
    private final static String MAX_DYNAMIC_RESULT_SETS = "max_dynamic_result_sets";
    private final static String DEFINITION = "definition";
    private final static String PARAMETERS_IN = "parameters_in";
    private final static String PARAMETERS_OUT = "parameters_out";
    private final static String NAME = "name";
    private final static String POSITION = "position";
    private final static String TYPE = "type";
    private final static String TYPE_OPTIONS = "type_options";
    private final static String IS_INOUT = "is_inout";
    private final static String IS_RESULT = "is_result";

    private final static String COMMENT_ANNOTATION1 = "//##";
    private final static String COMMENT_ANNOTATION2 = "##//";
    private final static String ENDPOINT = "endpoint";

    private final static String DISTINGUISHED_REGISTRATION_METHOD_NAME = "_register";

    private final static String CREATE_PROCEDURE_FORMAT = "CREATE PROCEDURE \"%s\".\"%s\" ()"
            + " LANGUAGE %s PARAMETER STYLE LIBRARY AS $$%s$$";

    private final static String DROP_PROCEDURE_FORMAT = "DROP PROCEDURE \"%s\".\"%s\"";

    private final static Object ENDPOINT_MAP_CACHE_KEY = new Object();

    private final SecurityService securityService;
    private final DXLService dxlService;
    private final EmbeddedJDBCService jdbcService;
    private final RoutineLoader routineLoader;

    @Inject
    public DirectServiceImpl(SecurityService securityService, DXLService dxlService, EmbeddedJDBCService jdbcService,
            RoutineLoader routineLoader) {
        this.securityService = securityService;
        this.dxlService = dxlService;
        this.jdbcService = jdbcService;
        this.routineLoader = routineLoader;
    }

    /* Service */

    @Override
    public void start() {
        // None
    }

    @Override
    public void stop() {
        // None
    }

    @Override
    public void crash() {
        // None
    }

    /* DirectService */

    @Override
    public void installLibrary(final PrintWriter writer, final HttpServletRequest request, final String module,
            final String definition, final String language) throws Exception {
        try (final JDBCConnection conn = jdbcConnection(request); final Statement statement = conn.createStatement()) {
            final TableName procName = ResourceHelper.parseTableName(request, module);
            final String create = String.format(CREATE_PROCEDURE_FORMAT, procName.getSchemaName(),
                    procName.getTableName(), language, definition);
            statement.execute(create);
            try {
                // Note: the side effect of the following call is to register
                // all functions in the new AIS
                final EndpointMap endpointMap = getEndpointMap(conn.getSession());
                reportLibraryFunctionCount(createJsonGenerator(writer), procName, endpointMap);
            } catch (RegistrationException e) {
                try {
                    final String drop = String.format(CREATE_PROCEDURE_FORMAT, procName.getSchemaName(),
                            procName.getTableName(), language, definition);
                    statement.execute(drop);
                } catch (Exception e2) {
                    LOG.error("Unable to remove invalid library " + module, e2);
                }
                throw new WebApplicationException(e, Status.INTERNAL_SERVER_ERROR);
            }
        }
    }

    @Override
    public void removeLibrary(final PrintWriter writer, final HttpServletRequest request, final String module)
            throws Exception {
        try (final JDBCConnection conn = jdbcConnection(request); final Statement statement = conn.createStatement()) {
            final TableName procName = ResourceHelper.parseTableName(request, module);
            final String drop = String.format(DROP_PROCEDURE_FORMAT, procName.getSchemaName(), procName.getTableName());
            statement.execute(drop);
            try {
                // Note: the side effect of the following call is to register
                // all functions in the new AIS
                final EndpointMap endpointMap = getEndpointMap(conn.getSession());
                reportLibraryFunctionCount(createJsonGenerator(writer), procName, endpointMap);
            } catch (RegistrationException e) {
                throw new WebApplicationException(e, Status.INTERNAL_SERVER_ERROR);
            }
        }
    }

    @Override
    public void reportStoredProcedures(final PrintWriter writer, final HttpServletRequest request, final String schema,
            final String module, final Session session, boolean functionsOnly) throws Exception {
        final String schemaResolved = schema.isEmpty() ? ResourceHelper.getSchema(request) : schema;

        if (module.isEmpty()) {
            checkSchemaAccessible(securityService, request, schemaResolved);
        } else {
            checkTableAccessible(securityService, request, new TableName(schemaResolved, module));
        }
        JsonGenerator json = createJsonGenerator(writer);
        AkibanInformationSchema ais = dxlService.ddlFunctions().getAIS(session);
        EndpointMap endpointMap = null;

        if (functionsOnly) {
            endpointMap = getEndpointMap(session);
        }

        if (module.isEmpty()) {
            // Get all routines in the schema.
            json.writeStartObject();
            {
                Schema schemaAIS = ais.getSchema(schema);
                if (schemaAIS != null) {
                    for (Map.Entry<String, Routine> routineEntry : schemaAIS.getRoutines().entrySet()) {
                        json.writeFieldName(routineEntry.getKey());
                        if (functionsOnly) {
                            reportLibraryFunctionMetadata(json, new TableName(schema, routineEntry.getKey()),
                                    endpointMap);
                        } else {
                            reportStoredProcedureDetails(json, routineEntry.getValue());
                        }
                    }
                }
            }
            json.writeEndObject();
        } else {
            // Get just the one routine.
            Routine routine = ais.getRoutine(schema, module);
            if (routine == null) {
                throw new NoSuchRoutineException(schema, module);
            }
            if (functionsOnly) {
                reportLibraryFunctionCount(json, new TableName(schema, module), endpointMap);
            } else {
                reportStoredProcedureDetails(json, routine);
            }
        }
        json.flush();
    }

    private void reportLibraryFunctionCount(final JsonGenerator json, final TableName module,
            final EndpointMap endpointMap) throws Exception {
        int count = 0;
        for (final Map.Entry<EndpointAddress, List<EndpointMetadata>> entry : endpointMap.getMap().entrySet()) {
            count += entry.getValue().size();
        }
        json.writeStartObject();
        json.writeNumberField(FUNCTIONS, count);
        json.writeEndObject();
        json.flush();
    }

    private void reportLibraryFunctionMetadata(final JsonGenerator json, final TableName module,
            final EndpointMap endpointMap) throws Exception {
        json.writeStartObject();
        json.writeArrayFieldStart(FUNCTIONS);
        {
            for (final Map.Entry<EndpointAddress, List<EndpointMetadata>> entry : endpointMap.getMap().entrySet()) {
                for (final EndpointMetadata em : entry.getValue()) {
                    json.writeString(em.toString());
                }
            }
        }
        json.writeEndArray();
        json.writeEndObject();
        json.flush();
    }

    private void reportStoredProcedureDetails(JsonGenerator json, Routine routine) throws IOException {
        json.writeStartObject();
        {
            json.writeStringField(LANGUAGE, routine.getLanguage());
            json.writeStringField(CALLING_CONVENTION, routine.getCallingConvention().name());
            json.writeNumberField(MAX_DYNAMIC_RESULT_SETS, routine.getDynamicResultSets());
            json.writeStringField(DEFINITION, routine.getDefinition());
            reportLibraryDetailsParams(PARAMETERS_IN, routine.getParameters(), Parameter.Direction.IN, json);
            reportLibraryDetailsParams(PARAMETERS_OUT, routine.getParameters(), Parameter.Direction.OUT, json);
        }
        json.writeEndObject();
        json.flush();
    }

    private void reportLibraryDetailsParams(String label, List<Parameter> parameters, Parameter.Direction direction,
            JsonGenerator json) throws IOException {
        json.writeArrayFieldStart(label);
        {
            for (int i = 0; i < parameters.size(); i++) {
                Parameter param = parameters.get(i);
                Parameter.Direction paramDir = param.getDirection();
                final boolean isInteresting;
                switch (paramDir) {
                case RETURN:
                    paramDir = Parameter.Direction.OUT;
                case IN:
                case OUT:
                    isInteresting = (paramDir == direction);
                    break;
                case INOUT:
                    isInteresting = true;
                    break;
                default:
                    throw new IllegalStateException("don't know how to handle parameter " + param);
                }
                if (isInteresting) {
                    json.writeStartObject();
                    {
                        json.writeStringField(NAME, param.getName());
                        json.writeNumberField(POSITION, i);
                        TInstance tInstance = param.tInstance();
                        TClass tClass = param.tInstance().typeClass();
                        json.writeStringField(TYPE, tClass.name().unqualifiedName());
                        json.writeObjectFieldStart(TYPE_OPTIONS);
                        {
                            for (Attribute attr : tClass.attributes())
                                json.writeObjectField(attr.name().toLowerCase(), tInstance.attributeToObject(attr));
                        }
                        json.writeEndObject();
                        json.writeBooleanField(IS_INOUT, paramDir == Parameter.Direction.INOUT);
                        json.writeBooleanField(IS_RESULT, param.getDirection() == Parameter.Direction.RETURN);
                    }
                    json.writeEndObject();
                }
            }
        }
        json.writeEndArray();
    }

    @Override
    public void invokeRestEndpoint(final PrintWriter writer, final HttpServletRequest request, final String method,
            final TableName procName, final String pathParams, final MultivaluedMap<String, String> queryParameters,
            final byte[] content, final MediaType[] responseType) throws Exception {
        try (JDBCConnection conn = jdbcConnection(request, procName.getSchemaName());) {

            boolean completed = false;
            boolean repeat = true;

            while (repeat) {
                try {
                    Direct.enter(procName.getSchemaName(), dxlService.ddlFunctions().getAIS(conn.getSession()));
                    Direct.getContext().setConnection(conn);
                    repeat = false;
                    conn.beginTransaction();
                    invokeRestFunction(writer, conn, method, procName, pathParams, queryParameters, content,
                            request.getContentType(), responseType);
                    conn.commitTransaction();
                    completed = true;
                } catch (RollbackException e) {
                    repeat = true;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                } finally {
                    try {
                        if (!completed) {
                            conn.rollbackTransaction();
                        }
                    } finally {
                        Direct.leave();
                    }
                }
            }
        }
    }

    /**
     * Invokes a function in a script library. The identity of the function is
     * determined from multiple factors including the schema name, the library
     * routine name, the URI of the request, the content type of the request,
     * etc. This method is called by {@link RestServiceImpl} which validates
     * security and supplies the JDBCConnection
     */

    private void invokeRestFunction(final PrintWriter writer, JDBCConnection conn, final String method,
            final TableName procName, final String pathParams, final MultivaluedMap<String, String> queryParameters,
            final byte[] content, final String requestType, final MediaType[] responseType) throws Exception {

        ParamCache cache = new ParamCache();
        final EndpointMap endpointMap = getEndpointMap(conn.getSession());
        final List<EndpointMetadata> list;
        synchronized (endpointMap) {
            list = endpointMap.getMap().get(new EndpointAddress(method, procName));
        }

        EndpointMetadata md = selectEndpoint(list, pathParams, requestType, responseType, cache);
        if (md == null) {
            // TODO - Is this the correct Exception? Is there a way to convey
            // this without logged stack trace?
            throw new MalformedRequestException("No matching endpoint");
        }

        final Object[] args = createArgsArray(pathParams, queryParameters, content, cache, md);

        final ScriptInvoker invoker = conn.getRoutineLoader()
                .getScriptInvoker(conn.getSession(), new TableName(procName.getSchemaName(), md.routineName)).get();
        Object result = invoker.invokeNamedFunction(md.function, args);

        switch (md.outParam.type) {

        case EndpointMetadata.X_TYPE_STRING:
            responseType[0] = MediaType.TEXT_PLAIN_TYPE;
            if (result != null) {
                writer.write(result.toString());
            } else if (md.outParam.defaultValue != null) {
                writer.write(md.outParam.defaultValue.toString());
            }
            break;

        case EndpointMetadata.X_TYPE_JSON:
            responseType[0] = MediaType.APPLICATION_JSON_TYPE;
            if (result != null) {
                writer.write(result.toString());
            } else if (md.outParam.defaultValue != null) {
                writer.write(md.outParam.defaultValue.toString());
            }
            break;

        case EndpointMetadata.X_TYPE_BYTEARRAY:
            responseType[0] = MediaType.APPLICATION_OCTET_STREAM_TYPE;
            // TODO: Unsupported - need to add a path for writing a stream
            break;

        default:
            // No response type specified
            responseType[0] = null;
            break;
        }
    }

    /**
     * Select a registered <code>EndpointMetadata</code> from the supplied list.
     * The first candidate in the list that matches the end-point pattern
     * definition and has a compatible request content type is selected. If
     * there are no matching endpoints this method return <code>null</code>.
     * 
     * @param list
     *            List of <code>EndpointMetadata</code> instances to chose from.
     * @param pathParams
     *            String containing any path parameters; empty if none. If the
     *            value is not empty, its first character is '/'.
     * @param requestType
     *            MIME type specified in the request
     * @param responseType
     *            One-long array in which the response MIME type that will be
     *            generated by the matching endpoint will be returned
     * @param cache
     *            A <code>ParamCache</code> instance in which partial results
     *            are cached
     * @return the selected <code>EndpointMetadata</code> or <code>null</code>.
     */
    EndpointMetadata selectEndpoint(final List<EndpointMetadata> list, final String pathParams,
            final String requestType, final MediaType[] responseType, ParamCache cache) {
        EndpointMetadata md = null;
        if (list != null) {
            for (final EndpointMetadata candidate : list) {
                if (candidate.pattern != null) {
                    Matcher matcher = candidate.getParamPathMatcher(cache, pathParams);
                    if (matcher.matches()) {
                        if (responseType != null && candidate.expectedContentType != null
                                && !requestType.startsWith(candidate.expectedContentType)) {
                            continue;
                        }
                        md = candidate;
                        break;
                    }
                } else {
                    if (pathParams == null || pathParams.isEmpty()) {
                        md = candidate;
                        break;
                    }
                }
            }
        }
        return md;
    }

    /**
     * Construct an argument array in preparations for calling a function.
     * Values of the arguments are extracted from elements of the REST request,
     * including a portion of the URI containing parameter values (the
     * <code>pathParams</code>), <code>queryParams</code> specified by text
     * after a '?' character in the URI, and the content of the request which
     * may be interpreted as a byte array, a String or a JSON-formatted string
     * in which elements can be specified by name.
     * 
     * @param pathParams
     *            String containing parameters specified as part of the URI path
     * @param queryParameters
     *            <code>MultivaluedMap</code> containing query parameters
     * @param content
     *            Content of the request body as a byte array, or
     *            <code>null</code> in the case of a GET request.
     * @param cache
     *            A cache for partial results
     * @param md
     *            The <code>EndpointMetadata</code> instance selected by
     *            {@link #selectEndpoint(List, String, String, MediaType[], ParamCache)}
     * @return the argument array
     * @throws Exception
     */
    Object[] createArgsArray(final String pathParams, final MultivaluedMap<String, String> queryParameters,
            final byte[] content, ParamCache cache, EndpointMetadata md) throws Exception {
        final Object[] args = new Object[md.inParams.length];
        for (int index = 0; index < md.inParams.length; index++) {
            final ParamMetadata pm = md.inParams[index];
            Object v = pm.source.value(pathParams, queryParameters, content, cache);
            args[index] = EndpointMetadata.convertType(pm, v);
        }
        return args;
    }

    static class EndpointMap {

        final RoutineLoader routineLoader;
        final Map<EndpointAddress, List<EndpointMetadata>> map = new HashMap<EndpointAddress, List<EndpointMetadata>>();

        EndpointMap(final RoutineLoader routineLoader) {
            this.routineLoader = routineLoader;
        }

        Map<EndpointAddress, List<EndpointMetadata>> getMap() {
            return map;
        }

        void populate(final AkibanInformationSchema ais, final Session session) throws RegistrationException {
            for (final Routine routine : ais.getRoutines().values()) {
                if (routine.getCallingConvention().equals(CallingConvention.SCRIPT_LIBRARY)
                        && routine.getDynamicResultSets() == 0 && routine.getParameters().isEmpty()) {
                    final String definition = routine.getDefinition();
                    final String schemaName = routine.getName().getSchemaName();
                    final String procName = routine.getName().getTableName();
                    try {
                        parseAnnotations(schemaName, procName, definition);
                    } catch (Exception e) {
                        throw new RegistrationException(e);
                    }
                    try {
                        final ScriptInvoker invoker = routineLoader.getScriptInvoker(session, routine.getName()).get();
                        invoker.invokeNamedFunction(DISTINGUISHED_REGISTRATION_METHOD_NAME,
                                new Object[] { new RestFunctionRegistrar() {
                                    @Override
                                    public void register(String specification) throws Exception {
                                        EndpointMap.this.register(routine.getName().getSchemaName(), routine.getName()
                                                .getTableName(), specification);
                                    }
                                } });
                    } catch (ExternalRoutineInvocationException e) {
                        if (e.getCause() instanceof NoSuchMethodError) {
                            throw new RegistrationException("No " + DISTINGUISHED_REGISTRATION_METHOD_NAME + " method",
                                    e.getCause());
                        }
                        Throwable previous = e;
                        Throwable current;
                        while ((current = previous.getCause()) != null && current != previous) {
                            if (current instanceof RegistrationException) {
                                throw (RegistrationException) current;
                            }
                            previous = current;
                        }
                        throw e;
                    } catch (RegistrationException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RegistrationException(e);
                    }
                }
            }
        }

        void parseAnnotations(final String schemaName, final String procName, final String definition) throws Exception {
            String[] lines = definition.split("\\n");
            String spec = "";
            for (final String s : lines) {
                String line = s.trim();
                if (line.startsWith(COMMENT_ANNOTATION1) || line.startsWith(COMMENT_ANNOTATION2)) {
                    line = line.substring(COMMENT_ANNOTATION1.length()).trim();
                    if (line.regionMatches(true, 0, ENDPOINT, 0, ENDPOINT.length())) {
                        if (!spec.isEmpty()) {
                            registerAnnotation(schemaName, procName, spec);
                        }
                        spec = line.substring(ENDPOINT.length()).trim();
                    } else {
                        if (!spec.isEmpty()) {
                            spec += " " + line;
                        }
                    }
                } else if (!spec.isEmpty()) {
                    registerAnnotation(schemaName, procName, spec);
                    spec = "";
                }
            }
            if (!spec.isEmpty()) {
                registerAnnotation(schemaName, procName, spec);
                spec = "";
            }
        }

        void registerAnnotation(final String schema, final String routine, final String spec) throws Exception {
            if (spec.startsWith("(")) {
                final Tokenizer tokens = new Tokenizer(spec, ", ");
                tokens.grouped = true;
                register(schema, routine, tokens.next(true));
            } else {
                register(schema, routine, spec);
            }
        }

        void register(final String schema, final String routine, final String spec) throws Exception {

            try {
                EndpointMetadata em = EndpointMetadata.createEndpointMetadata(schema, routine, spec);
                EndpointAddress ea = new EndpointAddress(em.method, new TableName(schema, em.name));

                synchronized (map) {
                    List<EndpointMetadata> list = map.get(ea);
                    if (list == null) {
                        list = new LinkedList<EndpointMetadata>();
                        map.put(ea, list);
                    }
                    list.add(em);
                }
            } catch (Exception e) {
                throw new RegistrationException("Invalid function specification: " + spec, e);
            }
        }
    }

    private EndpointMap getEndpointMap(final Session session) {
        final AkibanInformationSchema ais = dxlService.ddlFunctions().getAIS(session);
        return ais.getCachedValue(ENDPOINT_MAP_CACHE_KEY, new CacheValueGenerator<EndpointMap>() {

            @Override
            public EndpointMap valueFor(AkibanInformationSchema ais) {
                EndpointMap em = new EndpointMap(routineLoader);
                em.populate(ais, session);
                return em;
            }

        });
    }

    private JDBCConnection jdbcConnection(HttpServletRequest request) throws SQLException {
        return (JDBCConnection) jdbcService.newConnection(new Properties(), request.getUserPrincipal());
    }

    private JDBCConnection jdbcConnection(HttpServletRequest request, String schemaName) throws SQLException {
        // TODO: This is to make up for test situations where the
        // request is not authenticated.
        Properties properties = new Properties();
        if ((request.getUserPrincipal() == null) && (schemaName != null)) {
            properties.put("user", schemaName);
        }
        return (JDBCConnection) jdbcService.newConnection(properties, request.getUserPrincipal());
    }

    /**
     * Private unchecked wrapper to communicate errors in function
     * specifications
     * 
     */
    @SuppressWarnings("serial")
    private static class RegistrationException extends RuntimeException {

        RegistrationException(final Throwable t) {
            super(t);
        }

        RegistrationException(final String msg, final Throwable t) {
            super(msg, t);
        }
    }

}
