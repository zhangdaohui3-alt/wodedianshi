package com.wodedianshi.tv.db;

/**
 * Pure version predicate used under Room's write transaction.
 * A missing checkpoint is never a match for a previously loaded catalogue.
 */
final class CatalogueVersionPolicy {
    private CatalogueVersionPolicy() {}

    // Separate complete directory writes from lightweight favorite/selection edits.
    static final String STRUCTURE_VERSION_KEY="source_catalogue_structure_version";

    static long readStructureVersion(String stored,long checkpointVersion){
        if(stored==null)return checkpointVersion; // upgrade before first tracked write
        try{
            long value=Long.parseLong(stored);
            if(value>=0&&value<=checkpointVersion)return value;
        }catch(NumberFormatException ignored){}
        throw new IllegalStateException("Invalid catalogue structure version");
    }

    static boolean structureMatches(String base,String current){
        if(base==null||current==null)return false;
        try{
            long expected=Long.parseLong(base),actual=Long.parseLong(current);
            return expected>=0&&expected==actual;
        }catch(NumberFormatException invalid){return false;}
    }

    static boolean matches(long expected, Long observed) {
        return expected >= 0L && observed != null && observed.longValue() == expected;
    }
}
