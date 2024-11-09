package at.cath.autoupdatelib;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;


public class AutoUpdateLib implements ModInitializer {

    private final static String MOD_ID = "autoupdatelib";

    private final Map<String, String> modsToCheck = new HashMap<>();
    private final Logger logger = LoggerFactory.getLogger("AutoUpdateLib");

    @Override
    public void onInitialize() {
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            ModMetadata metadata = mod.getMetadata();

            var usesAutoUpdate = metadata.getDependencies().stream().anyMatch(it -> it.getModId().equals(MOD_ID));
            if (!usesAutoUpdate) return;

            var modId = metadata.getId();
            var contact = metadata.getContact();
            if (contact == null || contact.get("repo").isEmpty()) {
                logger.warn("Mod {} depends on AutoUpdateLib but does not specify a repo in fabric.mod.json", modId);
                return;
            }

            var githubUrl = contact.get("repo").get();

            modsToCheck.put(modId, githubUrl);
            logger.info("Picked up mod {} for auto-updates from {}", modId, githubUrl);
        });
    }
}
