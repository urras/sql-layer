/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
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

package com.foundationdb.ais.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import com.foundationdb.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultNameGenerator implements NameGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultNameGenerator.class);

    public static final String IDENTITY_SEQUENCE_PREFIX = "_sequence-";

    // Use 1 as default offset because the AAM uses tableID 0 as a marker value.
    static final int USER_TABLE_ID_OFFSET = 1;
    static final int IS_TABLE_ID_OFFSET = 1000000000;

    private final Set<String> fullTextPaths;
    private final Set<TableName> sequenceNames;
    private final SortedSet<Integer> tableIDSet;
    private final SortedSet<Integer> isTableIDSet;
    private final Map<Integer,Integer> indexIDMap;


    public DefaultNameGenerator() {
        this.fullTextPaths = new HashSet<>();
        sequenceNames = new HashSet<>();
        tableIDSet = new TreeSet<>();
        isTableIDSet = new TreeSet<>();
        indexIDMap = new HashMap<>();
    }

    public DefaultNameGenerator(AkibanInformationSchema ais) {
        this();
        mergeAIS(ais);
    }

    protected synchronized int getMaxIndexID() {
        int max = 1;
        for(Integer id : indexIDMap.values()) {
            max = Math.max(max, id);
        }
        return max;
    }

    @Override
    public synchronized int generateTableID(TableName name) {
        final int offset;
        if(TableName.INFORMATION_SCHEMA.equals(name.getSchemaName())) {
            offset = getNextTableID(true);
            assert offset >= IS_TABLE_ID_OFFSET : "Offset too small for IS table " + name + ": " + offset;
        } else {
            offset = getNextTableID(false);
            if(offset >= IS_TABLE_ID_OFFSET) {
                LOG.warn("Offset for table {} unexpectedly large: {}", name, offset);
            }
        }
        return offset;
    }

    @Override
    public synchronized int generateIndexID(int rootTableID) {
        Integer current = indexIDMap.get(rootTableID);
        int newID = 1;
        if(current != null) {
            newID += current;
        }
        indexIDMap.put(rootTableID, newID);
        return newID;
    }

    @Override
    public synchronized TableName generateIdentitySequenceName(TableName tableName) {
        TableName seqName = new TableName(tableName.getSchemaName(), IDENTITY_SEQUENCE_PREFIX + tableName.hashCode());
        return makeUnique(sequenceNames, seqName);
    }

    @Override
    public synchronized String generateJoinName(TableName parentTable, TableName childTable, List<JoinColumn> columns) {
        List<String> pkColNames = new LinkedList<>();
        List<String> fkColNames = new LinkedList<>();
        for (JoinColumn col : columns) {
            pkColNames.add(col.getParent().getName());
            fkColNames.add(col.getChild().getName());
        }
        return generateJoinName(parentTable, childTable, pkColNames, fkColNames);
    }

    @Override
    public synchronized String generateJoinName(TableName parentTable, TableName childTable, List<String> pkColNames, List<String> fkColNames) {
        String ret = String.format("%s/%s/%s/%s/%s/%s",
                parentTable.getSchemaName(),
                parentTable.getTableName(),
                Strings.join(pkColNames, ","),
                childTable.getSchemaName(),
                childTable, // TODO: This shold be getTableName(), but preserve old behavior for test existing output
                Strings.join(fkColNames, ","));
        return ret.toLowerCase().replace(',', '_');
    }

    @Override
    public synchronized String generateFullTextIndexPath(FullTextIndex index) {
        IndexName name = index.getIndexName();
        String proposed = String.format("%s.%s.%s", name.getSchemaName(), name.getTableName(), name.getName());
        return makeUnique(fullTextPaths, proposed);
    }

    @Override
    public synchronized void mergeAIS(AkibanInformationSchema ais) {
        sequenceNames.addAll(ais.getSequences().keySet());
        isTableIDSet.addAll(collectTableIDs(ais, true));
        tableIDSet.addAll(collectTableIDs(ais, false));
        indexIDMap.putAll(collectMaxIndexIDs(ais));
    }

    @Override
    public synchronized void removeTableID(int tableID) {
        isTableIDSet.remove(tableID);
        tableIDSet.remove(tableID);
    }

    /** Should be over-ridden by derived. */
    @Override
    public synchronized Set<String> getStorageNames() {
        return Collections.emptySet();
    }

    //
    // Private
    //

    /**
     * Get the next number that could be used for a table ID. The parameter indicates
     * where to start the search, but the ID will be unique across ALL tables.
     * @param isISTable Offset to start the search at.
     * @return Unique ID value.
     */
    private int getNextTableID(boolean isISTable) {
        int nextID;
        if(isISTable) {
            nextID = isTableIDSet.isEmpty() ? IS_TABLE_ID_OFFSET : isTableIDSet.last() + 1;
        } else {
            nextID = tableIDSet.isEmpty() ? USER_TABLE_ID_OFFSET : tableIDSet.last() + 1;
        }
        while(isTableIDSet.contains(nextID) || tableIDSet.contains(nextID)) {
            nextID += 1;
        }
        if(isISTable) {
            isTableIDSet.add(nextID);
        } else {
            tableIDSet.add(nextID);
        }
        return nextID;
    }

    //
    // Static
    //

    private static SortedSet<Integer> collectTableIDs(AkibanInformationSchema ais, boolean onlyISTables) {
        SortedSet<Integer> idSet = new TreeSet<>();
        for(Schema schema : ais.getSchemas().values()) {
            if(TableName.INFORMATION_SCHEMA.equals(schema.getName()) != onlyISTables) {
                continue;
            }
            for(Table table : schema.getTables().values()) {
                idSet.add(table.getTableId());
            }
        }
        return idSet;
    }

    public static Map<Integer,Integer> collectMaxIndexIDs(AkibanInformationSchema ais) {
        MaxIndexIDVisitor visitor = new MaxIndexIDVisitor();
        Map<Integer,Integer> idMap = new HashMap<>();
        for(Group group : ais.getGroups().values()) {
            visitor.reset();
            group.visit(visitor);
            idMap.put(group.getRoot().getTableId(), visitor.getMaxIndexID());
        }
        return idMap;
    }

    private static TableName makeUnique(Set<TableName> set, TableName original) {
        int counter = 1;
        TableName proposed = original;
        while(!set.add(proposed)) {
            proposed = new TableName(original.getSchemaName(), original.getTableName()  + "$" + counter++);
        }
        return proposed;
    }

    public static String makeUnique(Set<String> set, String original) {
        int counter = 1;
        String proposed = original;
        while(!set.add(proposed)) {
            proposed = original + "$" + counter++;
        }
        return proposed;
    }

    public static String schemaNameForIndex(Index index) {
        switch(index.getIndexType()) {
            case TABLE:
                return ((TableIndex)index).getTable().getName().getSchemaName();
            case GROUP:
                return ((GroupIndex)index).getGroup().getSchemaName();
            case FULL_TEXT:
                return ((FullTextIndex)index).getIndexedTable().getName().getSchemaName();
            default:
                throw new IllegalArgumentException("Unknown type: " + index.getIndexType());
        }
    }

    private static class MaxIndexIDVisitor extends AbstractVisitor {
        private int maxID;

        public MaxIndexIDVisitor() {
        }

        public void reset() {
            maxID = 0;
        }

        public int getMaxIndexID() {
            return maxID;
        }

        @Override
        public void visit(Index index) {
            maxID = Math.max(maxID, index.getIndexId());
        }
    }
}
