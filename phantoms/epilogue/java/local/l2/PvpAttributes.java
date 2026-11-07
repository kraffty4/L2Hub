package local.l2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.type.CrystalType;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;

/** Optional PvP gear eligibility only; native stone transactions and limits stay intact. */
public final class PvpAttributes {
    private static final Logger LOG=Logger.getLogger(PvpAttributes.class.getName());
    private PvpAttributes(){}
    static Properties load(Path file){
        Properties p=new Properties();
        if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
        catch(IOException e){LOG.warning("PvP attribute setting unreadable; native restrictions retained: "+e.getMessage());}
        return p;
    }
    private static final class Settings {
        static final Properties VALUES=load(Path.of("config/Custom/PvpAttributes.ini"));
        static final boolean WEAPONS=Boolean.parseBoolean(VALUES.getProperty("AllowAttributeOnPvpWeapons","False").trim());
        static final boolean ARMOR=Boolean.parseBoolean(VALUES.getProperty("AllowAttributeOnPvpArmor","False").trim());
    }
    public static boolean allowed(ItemTemplate item){
        if(item==null||!item.isPvpItem()||item.isHeroItem()||item.isForNpc()||item.isQuestItem()
            ||item.getDuration()>0||item.getTime()>0||item.getElementals()!=null)return false;
        CrystalType grade=item.getCrystalType();
        if(grade!=CrystalType.S&&grade!=CrystalType.S80&&grade!=CrystalType.S84)return false;
        if(item.isWeapon())return item.getItemType()!=WeaponType.NONE&&item.getItemType()!=WeaponType.FISHINGROD&&Settings.WEAPONS;
        if(item.isArmor())return switch(item.getBodyPart()){
            case HEAD,CHEST,FULL_ARMOR,LEGS,GLOVES,FEET -> Settings.ARMOR;
            default -> false;
        };
        return false;
    }
}
