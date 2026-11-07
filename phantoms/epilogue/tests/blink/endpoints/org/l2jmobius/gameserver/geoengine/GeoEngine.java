package org.l2jmobius.gameserver.geoengine;
import org.l2jmobius.gameserver.entity.Location;
/** Controlled geometry endpoint only; actual Blink computes direction and asks this endpoint. */
public final class GeoEngine {
    private static final GeoEngine INSTANCE=new GeoEngine();
    public static GeoEngine getInstance(){return INSTANCE;}
    public Location getValidLocation(int x,int y,int z,int dx,int dy,int dz,int instance){return new Location(dx,dy,dz);}
}
