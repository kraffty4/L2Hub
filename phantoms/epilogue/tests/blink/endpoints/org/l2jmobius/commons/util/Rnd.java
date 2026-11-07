package org.l2jmobius.commons.util;
/** Deterministic middle-of-range random endpoint. Native effect success formulas are unchanged. */
public final class Rnd {
    public static int get(int bound){return bound<=0?0:bound/2;}
    public static int get(int from,int to){return from>=to?from:from+(to-from)/2;}
    public static double get(){return 0.5;}
    public static double nextDouble(){return 0.5;}
    public static boolean nextBoolean(){return false;}
}
