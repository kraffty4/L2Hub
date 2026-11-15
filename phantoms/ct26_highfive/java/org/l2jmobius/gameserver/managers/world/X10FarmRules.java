package org.l2jmobius.gameserver.managers.world;

/** Формулы Зачистки без привязки к миру и кошельку. */
final class X10FarmRules
{
 static int rankBonus(int rank,int teams,int first,int last)
 {
  if (rank<1 || teams<1 || rank>teams) { throw new IllegalArgumentException("Invalid farm rank"); }
  return teams==1 ? first : first-(int)((long)(first-Math.min(first,last))*(rank-1)/(teams-1));
 }
 static long share(long pool,int index,int count)
 {
  return pool/count + (index<pool%count ? 1 : 0);
 }
 static long bossAt(long duration,int seconds) { return Math.max(duration/2,duration-seconds*1000L); }
 private X10FarmRules() { }
}
