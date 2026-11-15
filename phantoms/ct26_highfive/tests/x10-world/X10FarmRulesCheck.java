package org.l2jmobius.gameserver.managers.world;

/** Экономические инварианты: монотонность мест и сохранение каждой монеты при делении. */
public final class X10FarmRulesCheck
{
 public static void main(String[] args)
 {
  int checks=0;
  for (int teams=1;teams<=36;teams++)
  {
   int previous=Integer.MAX_VALUE;
   for (int rank=1;rank<=teams;rank++)
   {
    int value=X10FarmRules.rankBonus(rank,teams,100,20);
    if (value>previous || value<20 || value>100 || rank==1 && value!=100 || teams>1 && rank==teams && value!=20) { throw new AssertionError("rank bonus"); }
    previous=value;checks++;
   }
  }
  for (int pool=0;pool<=1000;pool++)
  {
   for (int count=1;count<=36;count++)
   {
    long total=0,min=Long.MAX_VALUE,max=0;
    for (int i=0;i<count;i++) { long share=X10FarmRules.share(pool,i,count);total+=share;min=Math.min(min,share);max=Math.max(max,share); }
    if (total!=pool || max-min>1) { throw new AssertionError("lost or uneven coins"); }checks++;
   }
  }
  for (long duration=60000;duration<=1800000;duration+=60000)
  {
   long at=X10FarmRules.bossAt(duration,60);
   if (at<duration/2 || at>=duration || duration>=120000 && at!=duration-60000) { throw new AssertionError("boss time"); }checks++;
  }
  System.out.println("X10 Farm rules: " + checks + " passed");
 }
}
