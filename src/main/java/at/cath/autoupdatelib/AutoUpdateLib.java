package at.cath.autoupdatelib;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


public class AutoUpdateLib implements ModInitializer {

    private final static String MOD_ID = "autoupdatelib";

    private final Map<String, UpdateEntry> modsToCheck = new HashMap<>();
    public final Logger logger = LoggerFactory.getLogger(MOD_ID);
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("https://github\\.com/([^/]+)/([^/]+)");
    private static final Path MODS_DIR = FabricLoader.getInstance().getGameDir().resolve("mods");

    record UpdateEntry(String githubUrl, String inferredVersion, Path oldJarPath) {
    }

    @Override
    public void onInitialize() {
        logger.info("Initialising AutoUpdateLib");
        // todo: maybe this is not initialised for all mods due to load order, potentially move to onjoin
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            ModMetadata metadata = mod.getMetadata();

            var origin = mod.getOrigin();
            // do not apply to dependencies
            if (!origin.getKind().equals(ModOrigin.Kind.PATH))
                return;

            var usesAutoUpdate = metadata.getDependencies().stream().anyMatch(it -> it.getModId().equals(MOD_ID));
            if (!usesAutoUpdate) return;

            var modId = metadata.getId();
            var contact = metadata.getContact();

            if (contact == null || contact.get("sources").isEmpty()) {
                logger.warn("Mod {} depends on AutoUpdateLib but does not specify a 'sources' repo in fabric.mod.json", modId);
                return;
            }

            var githubUrl = contact.get("sources").get();
            if (!GITHUB_URL_PATTERN.matcher(githubUrl).matches()) {
                logger.warn("Mod {} depends on AutoUpdateLib but the 'sources' repo is not a valid GitHub URL", modId);
                return;
            }

            var modVersion = metadata.getVersion().toString();

            // todo: under which circumstances does getPaths() return more than one?

            for (var path : origin.getPaths()) {
                logger.info("one potential path: {}", path);
            }

            modsToCheck.put(modId, new UpdateEntry(githubUrl, modVersion, origin.getPaths().getFirst()));
            logger.info("Picked up mod {} with version {} for auto-updates from {}", modId, modVersion, githubUrl);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("autoupdate")
                .then(argument("modToUpdate", StringArgumentType.string())
                        .suggests(this::suggestMods)
                        .executes(this::updateMod))));
    }

    private CompletableFuture<Suggestions> suggestMods(CommandContext<ServerCommandSource> context,
                                                       SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();
        modsToCheck.keySet().stream()
                .filter(modId -> modId.toLowerCase().startsWith(input))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private int updateMod(CommandContext<ServerCommandSource> context) {
        var modId = StringArgumentType.getString(context, "modToUpdate");
        if (!modsToCheck.containsKey(modId)) {
            inform(context, modId, "Mod not found", Formatting.RED);
            return 0;
        }

        var modInfo = modsToCheck.get(modId);
        UpdateService updateService = new UpdateService(modInfo.githubUrl, logger);

        try {
            var releaseInfoOpt = updateService.getLatestReleaseInfo();
            if (releaseInfoOpt.isEmpty()) {
                inform(context, modId, "Failed to fetch latest release info", Formatting.RED);
                return 0;
            }

            var releaseInfo = releaseInfoOpt.get();
            var releaseVersion = releaseInfo.version();
            if (releaseVersion.contains(modInfo.inferredVersion)) {
                inform(context, modId, "Already up to date! (" + modInfo.inferredVersion + " ~= " + releaseVersion + ")",
                        Formatting.YELLOW);
                return 0;
            }

            // todo: construct file path somewhere else?
            String fileName = modId + "-" + releaseVersion + ".jar";
            updateService.downloadRelease(releaseInfo, MODS_DIR.resolve(fileName), modInfo.oldJarPath);

        } catch (IOException | URISyntaxException | InterruptedException e) {
            logger.error("Failed to fetch latest release for mod {}", modId, e);
            inform(context, modId, "Failed to fetch latest release info", Formatting.RED);
        }

        inform(context, modId, "Update successful! Restart to apply changes", Formatting.GREEN);
        Text.literal("").withColor(Formatting.GREEN.getColorValue());

        return 1;
    }

    private static void inform(CommandContext<ServerCommandSource> context, String modId, String msg, Formatting colour) {
        context.getSource().sendFeedback(() -> Text.literal(modId + ": " + msg).withColor(colour.getColorValue()), false);
    }

}
