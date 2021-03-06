package NeoIntegration;

import PageCacheSort.Sorter;
import bptree.impl.DiskCache;
import bptree.impl.IndexBulkLoader;
import bptree.impl.IndexTree;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.impl.store.InvalidRecordException;
import org.neo4j.tooling.GlobalGraphOperations;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserters;

import java.io.*;
import java.util.*;

/**
 * For loading *.owl files generated by the LUBM tool into Neo4j.
 * This involves converting from RDF Triples to the property graph model.
 */
public class BulkLUBMDataLoader {
    public BatchInserter inserter;
    //private final String DB_PATH = "/Users/max/Downloads/neo4j/data/graph.db";
    public static final String DB_PATH = "graph.db/";
    public static final String LUBM_INDEX_PATH = "lubm50Index.db";
    public static final String INDEX_METADATA_PATH = "pathIndexMetaData.dat";
    //public ArrayList<Long[]> keys;
    //String uriDir = "/Users/max/Desktop/lubm_data/csvData/";
    String uriDir = "csvData/";
    File[] owlFiles;
    HashMap<String, Label> labels = new HashMap<>();
    HashMap<String, RelationshipType> relationships = new HashMap<>();
    HashMap<String, Long> nodes = new HashMap<>();
    HashMap<Integer, Sorter> sorters = new HashMap<>();
    HashMap<RelationshipType, Integer> relationshipTypes = new HashMap<>();
    Map<String, Long> pathMap = new HashMap<>(); //relationship types to path ids
    Map<Integer, IndexTree> indexes = new HashMap<>();
    StringBuilder strBulder;
    LinkedList<String> prettyPaths = new LinkedList<>();
    RelationshipType headOf;
    RelationshipType takesCourse;


    public static void main( String[] args ) throws IOException {

        BulkLUBMDataLoader bulkLUBMDataLoader = new BulkLUBMDataLoader();

        bulkLUBMDataLoader.bulkLoad();
        /*
        bulkLUBMDataLoader.doCypherQueries();
        bulkLUBMDataLoader.getPathsRelationshipPerspective();
        bulkLUBMDataLoader.getPathsAll();

        bulkLUBMDataLoader.sortKeys();
        try(PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(INDEX_METADATA_PATH, false)))) {
        for (Sorter sorter : bulkLUBMDataLoader.sorters.values()) {
                long root = bulkLUBMDataLoader.buildIndex(sorter);
                out.println(sorter.keySize +","+root);
            }
        }
        */
    }

    public BulkLUBMDataLoader() throws IOException {

        File deleteIndex = new File(LUBM_INDEX_PATH);
        FileUtils.deleteFile(deleteIndex);
        owlFiles = new File(uriDir).listFiles();

        //sorters.put(3, new Sorter(3));
        //sorters.put(4, new Sorter(4));
        //sorters.put(5, new Sorter(5));
    }

    public void bulkLoad() throws IOException {
        File deleteGraph = new File(DB_PATH);
        FileUtils.deleteRecursively(deleteGraph);
        inserter = BatchInserters.inserter(DB_PATH);
        try{
            for(File file : owlFiles) {
                System.out.println("Importing: " + file.getName());
                fileParser(file);
            }
        }
        finally {
            inserter.shutdown();
        }
    }

    public void sortKeys() throws IOException {
            System.out.println("Sorting keys");
        for(Sorter sorter : sorters.values()){
            sorter.sort();
        }
    }

    public long buildIndex(Sorter sorter) throws IOException {
        DiskCache sortedDisk = sorter.getSortedDisk();
        DiskCache disk = DiskCache.persistentDiskCache(sorter.toString() + LUBM_INDEX_PATH, false);
        BulkPageSource sortedDataSource = new BulkPageSource(sortedDisk, sorter.finalPageId());

        IndexBulkLoader bulkLoader = new IndexBulkLoader(disk, sorter.finalPageId(), sorter.keySize);
        IndexTree tree = bulkLoader.run();
        System.out.println("Done. Root for this index (SAVE THIS VALUE!): " + tree.rootNodeId);

        disk.shutdown();
        sortedDisk.shutdown();
        return tree.rootNodeId;
    }


    private void insert(Triple triple){
        long thisNode = getOrCreateNode(triple.subjectType, triple.subjectURI);
        if(triple.predicate.equals("name") || triple.predicate.equals("type") || triple.predicate.equals("telephone") || triple.predicate.equals("emailAddress")){
            if(triple.predicate.equals("type")){
                inserter.setNodeProperty(thisNode, triple.predicate, triple.objectType);
            }
            else{
                try {
                    //inserter.setNodeProperty(thisNode, triple.predicate, triple.objectURI); //these properties don't seem to work sometimes.
                }
                catch(InvalidRecordException e){
                    System.out.println("Error writing: " + triple + "\n" + e.getMessage());
                }
            }
        }
        else{
            long otherNode = getOrCreateNode(triple.objectType, triple.objectURI);
            RelationshipType relationship = relationships.get(triple.predicate);
            if (relationship == null) {
                relationship = DynamicRelationshipType.withName(triple.predicate);
                relationships.put(triple.predicate, relationship);
            }
            inserter.createRelationship(thisNode, otherNode, relationship, null);
        }
    }

    private long getOrCreateNode(String label, String uri){
        Label typeLabel = labels.get(label);
        if(typeLabel == null) {
            typeLabel = DynamicLabel.label(label);
            labels.put(label, typeLabel);
        }
        Long foundNode = nodes.get(uri);
        if(foundNode == null){
            Map<String, Object> properties = new HashMap<>();
            properties.put("uri", uri);
            foundNode = inserter.createNode(properties, typeLabel);
            //inserter.setNodeProperty(foundNode, "uri", uri);
            nodes.put(uri, foundNode);
        }
        return foundNode;
    }

    private List<Triple> fileParser(File file){
        String line;
        LinkedList<Triple> triples = new LinkedList<>();
        try {
            // FileReader reads text files in the default encoding.
            BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
            Triple triple = new Triple();
            while((line = bufferedReader.readLine()) != null) {
                triple.setAll((Arrays.asList((line.replaceAll("\\s", "")).split(","))));
                insert(triple);
            }
            bufferedReader.close();
        }
        catch(FileNotFoundException ex) {
            System.out.println(
                    "Unable to open file '" +
                            file.getAbsolutePath() + "'");
        }
        catch(IOException ex) {
            System.out.println(
                    "Error reading file '"
                            + file.getAbsolutePath() + "'");
        }
        return triples;
    }



    private void getPathsAll() throws IOException{
        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase( DB_PATH );
        GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
        double count = 0;
        double totalRels;
        try ( Transaction tx = db.beginTx()) {
            for (RelationshipType relType : ggo.getAllRelationshipTypes()) {
                totalRels = IteratorUtil.count(ggo.getAllRelationships());
                for (Relationship relationship1 : ggo.getAllRelationships()) {
                    count++;
                    if (count % 1000 == 0) {
                        printStats(pathMap, count, totalRels);
                    }
                    Node node1 = relationship1.getStartNode();
                    Node node2 = relationship1.getEndNode();
                    //addPath(node1, relationship1, node2);
                    for (Relationship relationship2 : node2.getRelationships()) {
                        if (relationship2.getId() == relationship1.getId()) {
                            continue;
                        }
                        Node node3 = relationship2.getOtherNode(node2);
                        //addPath(node1, relationship1, node2, relationship2, node3);
                        for (Relationship relationship3 : node3.getRelationships()) {
                            if (relationship2.getId() == relationship3.getId()) {
                                continue;
                            }
                            Node node4 = relationship3.getOtherNode(node3);
                            addPath(node1, relationship1, node2, relationship2, node3, relationship3, node4);
                        }
                    }
                }
            }
        }
        System.out.println();
        try(PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter("BulkLUBMLoaderLog" + System.currentTimeMillis() +".txt", true)))) {
            for (String key : pathMap.keySet()) {
                System.out.println("Path: " + key + " , entries: " + pathMap.get(key));
                out.println("Path: " + key + " , entries: " + pathMap.get(key));
            }
        }
    }

    private void doCypherQueries() throws IOException {
        getPathCypher(1136874830,"MATCH (x)-[:takesCourse]->(y)<-[:teacherOf]-(z)");
        getPathCypher(90603815, "MATCH (x)-[:memberOf]->(y)-[:subOrganizationOf]->(z)");
        getPathCypher(1491269145, "MATCH (x)-[:memberOf]->(y)<-[:subOrganizationOf]-(z)");
        getPathCypher(1628983526, "MATCH (x)<-[:headOf]-(y)-[:worksFor]->(z)<-[:subOrganizationOf]-(w)");
        getPathCypher(1924021844, "MATCH (x)-[:hasAdvisor]->(y)-[:teacherOf]->(z)<-[:takesCourse]-(w)");
        getPathCypher(1084110810, "MATCH (x)<-[:headOf]-(y)-[:worksFor]->(z)-[:subOrganizationOf]->(w)");
        getPathCypher(35729895, "MATCH (x)-[:worksFor]->(y)");
        getPathCypher(1947276320, "MATCH (x)-[:undergraduateDegreeFrom]->(y)<-[:subOrganizationOf]-(z)<-[:memberOf]-(w)");
        getPathCypher(649439727,"MATCH (x)-[:memberOf]->(y)");
    }

    private void getPathCypher(long pathID, String cypher) throws IOException {
        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
        GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
        double count = 0;
        double totalRels;

        try (Transaction tx = db.beginTx()) {
            for (RelationshipType relType : ggo.getAllRelationshipTypes()) {
                if (relType.name().equals("memberOf") ||
                        relType.name().equals("worksFor") ||
                        relType.name().equals("headOf") ||
                        relType.name().equals("hasAdvisor") ||
                        relType.name().equals("takesCourse") ||
                        relType.name().equals("teacherOf") ||
                        relType.name().equals("subOrganizationOf") ||
                        relType.name().equals("undergraduateDegreeFrom")) {
                    relationshipTypes.put(relType, 1);
                }
                if (relType.name().equals("headOf")) {
                    headOf = relType;
                }
                if (relType.name().equals("takesCourse")) {
                    takesCourse = relType;
                }
            }
            totalRels = IteratorUtil.count(ggo.getAllRelationships());

            Result queryAResult = db.execute(cypher);
            while(queryAResult.hasNext()){
                Map<String, Object> result = queryAResult.next();
                long[] key = new long[result.size() + 1];
                int i = 1;
                key[0] = pathID;
                for(Object val : result.values()){
                    key[i++] = new Long(val.toString());
                }
                count++;
            }
        }
        System.out.println("Found: " + count);
    }

    private void getPathsRelationshipPerspective() throws IOException{
    GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase( DB_PATH );
    GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
    double count = 0;
    double totalRels;

    try ( Transaction tx = db.beginTx()) {
        for(RelationshipType relType : ggo.getAllRelationshipTypes()){
            if(relType.name().equals("memberOf") ||
                    relType.name().equals("worksFor") ||
                    relType.name().equals("headOf") ||
                    relType.name().equals("hasAdvisor") ||
                    relType.name().equals("takesCourse") ||
                    relType.name().equals("teacherOf") ||
                    relType.name().equals("subOrganizationOf") ||
                    relType.name().equals("undergraduateDegreeFrom")){
                relationshipTypes.put(relType,1);
            }
            if(relType.name().equals("headOf")){
                headOf = relType;
            }
            if(relType.name().equals("takesCourse")){
                takesCourse = relType;
            }
        }
        totalRels = IteratorUtil.count(ggo.getAllRelationships());

        for(Relationship relationship1 : ggo.getAllRelationships()){
            count++;
/*
            if(count % 50 != 0) {
                continue;
            }
*/
            if(count % 10000 == 0) {
                printStats(pathMap, count, totalRels);
            }
            if(badRel(relationship1)){continue;}
            Node node1 = relationship1.getStartNode();
            Node node2 = relationship1.getEndNode();
            addPathIfValid(node1, relationship1, node2);
            for(Relationship relationship2 : node2.getRelationships()){
                if(relationship2.getId() == relationship1.getId()){
                    continue;
                }
                if(badRel(relationship2)){continue;}
                Node node3 = relationship2.getOtherNode(node2);
                addPathIfValid(node1, relationship1, node2, relationship2, node3);
                for(Relationship relationship3 : node3.getRelationships()){
                    if(relationship2.getId() == relationship3.getId()){continue;}
                    if(badRel(relationship3)){continue;}
                    Node node4 = relationship3.getOtherNode(node3);
                    addPathIfValid(node1, relationship1, node2, relationship2, node3, relationship3, node4);
                }
            }
        }
    }
    System.out.println();
    try(PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter("BulkLUBMLoaderLog.txt", false)))) {
        for (String key : pathMap.keySet()) {
            System.out.println("Path: " + key + " , entries: " + pathMap.get(key));
            out.println("Path: " + key + " , entries: " + pathMap.get(key));
        }
    }
}

    private void addPathIfValid(Node node1, Relationship relationship1, Node node2) throws IOException {
        if(relationship1.getType().name().equals("worksFor")){
            PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2);
            sorters.get(3).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId()});
            updateStats(pathMap, builder);
        }
        else if (relationship1.getType().name().equals("memberOf")){
            PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2);
            sorters.get(3).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId()});
            updateStats(pathMap, builder);
        }
    }

    private void addPathIfValid(Node node1, Relationship relationship1, Node node2, Relationship relationship2, Node node3) throws IOException {
        if((relationship1.getType().name().equals("takesCourse") && relationship2.getType().name().equals("teacherOf"))){
            PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2, relationship2, node3);
            sorters.get(4).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId(), node3.getId()});
            updateStats(pathMap, builder);
        }

        if(relationship1.getType().name().equals("memberOf") && relationship2.getType().name().equals("subOrganizationOf")){
            PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2, relationship2, node3);
            sorters.get(4).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId(), node3.getId()});
            updateStats(pathMap, builder);
        }

        if(relationship1.getType().name().equals("worksFor") && relationship2.getType().name().equals("subOrganizationOf") && node1.hasRelationship(headOf)){
            PathIDBuilder builder = new PathIDBuilder(node1.getSingleRelationship(headOf, Direction.BOTH).getEndNode(), node1.getSingleRelationship(headOf, Direction.BOTH), node1, relationship1, node2, relationship2, node3);
            sorters.get(5).addUnsortedKey(new long[]{builder.buildPath(),node1.getSingleRelationship(headOf, Direction.BOTH).getEndNode().getId(), node1.getId(), node2.getId(), node3.getId()});
            updateStats(pathMap, builder);
        }
        if(relationship1.getType().name().equals("hasAdvisor") && relationship2.getType().name().equals("teacherOf")){
            for(Relationship relationship3 : node3.getRelationships(takesCourse)){
                Node node4 = relationship3.getOtherNode(node3);
                if(node4.getId() == node1.getId()) {
                    PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2, relationship2, node3, relationship3, relationship3.getOtherNode(node3));
                    sorters.get(4).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId(), node3.getId()});
                    updateStats(pathMap, builder);
                }
            }
        }
    }

    private void addPathIfValid(Node node1, Relationship relationship1, Node node2, Relationship relationship2, Node node3, Relationship relationship3, Node node4) throws IOException {
       if((relationship1.getType().name().equals("undergraduateDegreeFrom") && relationship2.getType().name().equals("subOrganizationOf") && relationship3.getType().name().equals("memberOf") &&
                (node1.getId() == node4.getId()))){
            PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2, relationship2, node3, relationship3, node4);
            sorters.get(4).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId(), node3.getId()});
            updateStats(pathMap, builder);
        }
    }
    //
    private void addPath(Node node1, Relationship relationship1, Node node2) throws IOException {
        PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2);
        sorters.get(3).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId()});
        updateStats(pathMap, builder);
    }
    private void addPath(Node node1, Relationship relationship1, Node node2, Relationship relationship2, Node node3) throws IOException {
        PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2, relationship2, node3);
        sorters.get(4).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId(), node3.getId()});
        updateStats(pathMap, builder);
    }

    private void addPath(Node node1, Relationship relationship1, Node node2, Relationship relationship2, Node node3, Relationship relationship3, Node node4) throws IOException {
        PathIDBuilder builder = new PathIDBuilder(node1, relationship1, node2, relationship2, node3, relationship3, node4);
        sorters.get(5).addUnsortedKey(new long[]{builder.buildPath(), node1.getId(), node2.getId(), node3.getId(), node4.getId()});
        updateStats(pathMap, builder);
    }

    private void printStats(Map<String, Long> pathMap, double count, double totalRels){
        if(strBulder != null){
            int b = strBulder.toString().length();
            for(int i = 0; i < b; i++){System.out.print("\b");}
        }
        strBulder = new StringBuilder();
        Calendar cal = Calendar.getInstance();
        strBulder.append("Progress: ").append(count).append("  |  ").append((int) ((count / totalRels) * 100)).append("% complete. Paths: ").append(pathMap.size()).append(", Last Updated: ").append(cal.get(Calendar.HOUR_OF_DAY)).append(":").append(cal.get(Calendar.MINUTE));

        System.out.print("\r" + strBulder.toString());
    }

    private void updateStats(Map<String, Long> pathMap, PathIDBuilder builder){
        if(!pathMap.containsKey(builder.toString())){
            pathMap.put(builder.toString(), 0l);
            prettyPaths.add(builder.prettyPrint());
        }
        pathMap.put(builder.toString(), pathMap.get(builder.toString()) + 1);
    }

    private boolean badRel(Relationship rel){
        return relationshipTypes.get(rel.getType()) == null;
    }

    /*
    private void getPathsNodesPerspective() throws IOException {
        long count = 0;
        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase( DB_PATH );
        GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
        try ( Transaction tx = db.beginTx()) {
            int i = 0;
            for (Node node1 : ggo.getAllNodes()) {
                i++;
                if (i % 50000 == 0) {System.out.println(i);}
                for (Relationship relationship1 : node1.getRelationships()) {
                    Node node2 = relationship1.getOtherNode(node1);
                    for (Relationship relationship2 : node2.getRelationships()) {
                        Node node3 = relationship2.getOtherNode(node2);
                        if (relationship1.getId() != relationship2.getId()) {
                            if (validPath(relationship1, relationship2, null)) {
                               doInsertion(node1, node2, node3, relationship1, relationship2, relationshipMap);
                            }
                            for(Relationship relationship3 : node3.getRelationships()){
                                Node node4 = relationship3.getOtherNode(node3);
                                if(relationship2.getId() != relationship3.getId()){
                                    if (validPath(relationship1, relationship2, relationship3)) {
                                        doInsertion(node1, node2, node3, node4, relationship1, relationship2, relationship3, relationshipMap);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("\n # of keys written to unsorted disk: " + count);
        //for(String pretty : prettyPaths){
        //    System.out.println(pretty);
        //}
        for(String key : relationshipMap.keySet()){
            System.out.println("PathID: " + key +  ", count: " + relationshipMap.get(key));
        }
    }
    private void doInsertion(Node node1, Node node2, Node node3, Relationship relationship1, Relationship relationship2, Map<String, Long> relationshipMap) throws IOException {
        PathIDBuilder pathBuilder = new PathIDBuilder(node1, node2, relationship1, relationship2);
        Long pathId = pathBuilder.buildPath();
        if(!relationshipMap.containsKey(pathId)){
            relationshipMap.put(pathBuilder.toString(), 0l);
            prettyPaths.add(pathBuilder.prettyPrint());
        }
        relationshipMap.put(pathBuilder.toString(), relationshipMap.get(pathId) + 1);
        Long[] key = new Long[]{pathId, node1.getId(), node2.getId(), node3.getId()};
        sorters.get(key.length).addUnsortedKey(key);
    }

    private void doInsertion(Node node1, Node node2, Node node3, Node node4, Relationship relationship1, Relationship relationship2, Relationship relationship3, Map<String, Long> relationshipMap) throws IOException {
        PathIDBuilder pathBuilder = new PathIDBuilder(node1, node2, node3, relationship1, relationship2, relationship3);
        Long pathId = pathBuilder.buildPath();
        if(!relationshipMap.containsKey(pathId)){
            relationshipMap.put(pathBuilder.toString(), 0l);
            prettyPaths.add(pathBuilder.prettyPrint());
        }
        relationshipMap.put(pathBuilder.toString(), relationshipMap.get(pathId) + 1);
        Long[] key = new Long[]{pathId, node1.getId(), node2.getId(), node3.getId(), node4.getId()};
        sorters.get(key.length).addUnsortedKey(key);
    }

    private boolean validPath(Relationship relA, Relationship relB, Relationship relC){
        boolean valid = false;
        //valid = valid || (relA.getType().name().equals("worksFor"));
        //valid = valid || (relA.getType().name().equals("memberOf"));
        valid = (relA.getType().name().equals("memberOf") && relB.getType().name().equals("subOrganizationOf") && relC == null);
        valid = valid || (relA.getType().name().equals("takesCourse") && relB.getType().name().equals("teacherOf") && relC == null);
        valid = valid || (relA.getType().name().equals("memberOf") && relB.getType().name().equals("undergraduateDegreeFrom") && relC.getType().name().equals("subOrganizationOf"));
        return valid;
    }
    */

public class Triple{
    public String subjectType;
    public String subjectURI;
    public String predicate;
    public String objectType;
    public String objectURI;
    public Triple(){}
    public Triple(List<String> csvLine){
        subjectType = csvLine.get(0);
        subjectURI = csvLine.get(1);
        predicate = csvLine.get(2);
        objectType = csvLine.get(3);
        objectURI = csvLine.get(4);
    }
    public void setAll(List<String> csvLine){
        subjectType = csvLine.get(0);
        subjectURI = csvLine.get(1);
        predicate = csvLine.get(2);
        objectType = csvLine.get(3);
        objectURI = csvLine.get(4);
    }
    public String toString(){
        return subjectURI + " " + predicate + " " + objectURI;
    }
}
}
