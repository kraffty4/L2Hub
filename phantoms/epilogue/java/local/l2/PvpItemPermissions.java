package local.l2;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.logging.Logger;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.instance.Item;

/** Normal use of permanent PvP equipment without removing its combat identity. */
public final class PvpItemPermissions {
    private PvpItemPermissions(){}
    private static final class Settings {
        static final Properties VALUES=load();
        static final boolean TRADE=enabled("AllowPvpItemTrade"),SALE=enabled("AllowPvpItemSale"),WAREHOUSE=enabled("AllowPvpItemWarehouse"),FREIGHT=enabled("AllowPvpItemFreight");
        static Properties load(){
            Properties p=new Properties();Path file=Path.of("config/Custom/PvpItemPermissions.ini");
            if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
            catch(IOException e){Logger.getLogger(PvpItemPermissions.class.getName()).warning("PvP permissions unreadable; native restrictions retained: "+e.getMessage());}
            return p;
        }
        static boolean enabled(String key){return Boolean.parseBoolean(VALUES.getProperty(key,"False").trim());}
    }
    static boolean eligible(ItemTemplate t){
        return t!=null&&t.isPvpItem()&&(t.isWeapon()||t.isArmor())&&!t.isHeroItem()&&!t.isQuestItem()
            &&!t.isForNpc()&&t.getDuration()<=0&&t.getTime()<=0;
    }
    public static boolean trade(ItemTemplate t){return eligible(t)&&Settings.TRADE;}
    public static boolean sale(ItemTemplate t){return eligible(t)&&Settings.SALE;}
    public static boolean warehouse(ItemTemplate t){return eligible(t)&&Settings.WAREHOUSE;}
    public static boolean freight(ItemTemplate t){return eligible(t)&&Settings.FREIGHT;}
    public static boolean augmentedTrade(Item item){return item!=null&&trade(item.getTemplate());}
    public static boolean augmentedSale(Item item){return item!=null&&sale(item.getTemplate());}
}
