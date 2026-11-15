package org.l2jmobius.gameserver.managers.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/** Площадки Зачистки: диапазон уровня, неповторяющаяся смена и проверка пути спавна. */
final class X10FarmArenas
{
 record Arena(String id, String ru, String en, int min, int max, Location center) { }
 private static List<Arena> arenas = List.of();
 static void load() throws Exception
 {
  final var factory = DocumentBuilderFactory.newInstance();
  factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
  final var nodes = factory.newDocumentBuilder().parse(Path.of("./data/L2HubX10FarmArenas.xml").toFile()).getDocumentElement().getElementsByTagName("arena");
  final List<Arena> loaded = new ArrayList<>();
  final java.util.Set<String> ids = new java.util.HashSet<>();
  for (int i = 0; i < nodes.getLength(); i++)
  {
   final Element e = (Element) nodes.item(i);
   final Arena a = new Arena(e.getAttribute("id"), e.getAttribute("ru"), e.getAttribute("en"), integer(e,"min"), integer(e,"max"), new Location(integer(e,"x"), integer(e,"y"), integer(e,"z")));
   if (!a.id().matches("[a-z_]+") || !ids.add(a.id()) || a.min()<1 || a.max()>85 || a.min()>a.max()) { throw new IllegalArgumentException("Invalid farm arena: " + a.id()); }
   loaded.add(a);
  }
  for (int level=1; level<=85; level++)
  {
   final int value=level;
   if (loaded.stream().filter(a -> a.min()<=value && a.max()>=value).count()<2) { throw new IllegalArgumentException("Need two farm arenas for level " + level); }
  }
  arenas=List.copyOf(loaded);
 }
 private static int integer(Element e,String key) { return Integer.parseInt(e.getAttribute(key)); }
 static Arena choose(int level)
 {
  final List<Arena> pool=arenas.stream().filter(a -> a.min()<=level && a.max()>=level).toList();
  if (pool.isEmpty()) { throw new IllegalStateException("No farm arena for level " + level); }
  final String key="L2HubX10FarmArena" + pool.getFirst().min();
  final long previous=GlobalVariablesManager.getInstance().getLong(key,-1);
  final int index=L2HubX10Config.FARM_ARENA_RANDOM && previous>=0
   ? (Math.floorMod(previous,pool.size()) + ThreadLocalRandom.current().nextInt(1,pool.size())) % pool.size()
   : (int)Math.floorMod(previous+1,pool.size());
  GlobalVariablesManager.getInstance().set(key,index);
  GlobalVariablesManager.getInstance().storeMe();
  return pool.get(index);
 }
 static Location point(Location center, int dx, int dy, int instance)
 {
  final var geo=GeoEngine.getInstance();
  final int x=center.getX()+dx, y=center.getY()+dy;
  final int z=geo.getHeight(x,y,center.getZ());
  return Math.abs(z-center.getZ())<=200 && geo.canMoveToTarget(center.getX(),center.getY(),center.getZ(),x,y,z,instance)
   ? new Location(x,y,z) : new Location(center.getX(),center.getY(),center.getZ());
 }
 private X10FarmArenas() { }
}
