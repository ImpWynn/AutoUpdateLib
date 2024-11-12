package at.cath.autoupdatelib;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;


public class AutoUpdateLib implements ModInitializer {

    private final static String MOD_ID = "autoupdatelib";

    private final Map<String, UpdateEntry> modsToCheck = new HashMap<>();
    public final Logger logger = LoggerFactory.getLogger(MOD_ID);
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("https://github\\.com/([^/]+)/([^/]+)");

    record UpdateEntry(String githubUrl, String inferredVersion, Path oldJarPath) {
    }

    @Override
    public void onInitialize() {
        logger.info("AutoUpdateLib fetching mods...");

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
            modsToCheck.put(modId, new UpdateEntry(githubUrl, modVersion, origin.getPaths().getFirst()));
            logger.info("Picked up mod {} with version {} for auto-updates from {}", modId, modVersion, githubUrl);
        });


        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("autoupdate")
                .then(argument("modToUpdate", StringArgumentType.string())
                        .suggests(this::suggestMods)
                        .executes(this::updateMod))));
    }

    private CompletableFuture<Suggestions> suggestMods(CommandContext<FabricClientCommandSource> context,
                                                       SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();
        modsToCheck.keySet().stream()
                .filter(modId -> modId.toLowerCase().startsWith(input))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private int updateMod(CommandContext<FabricClientCommandSource> context) {
        var modId = StringArgumentType.getString(context, "modToUpdate");
        if (!modsToCheck.containsKey(modId)) {
            inform(context, modId, "Mod not found", Formatting.RED);
            return 0;
        }

        var modInfo = modsToCheck.get(modId);
        UpdateService updateService = new UpdateService(modInfo.githubUrl, logger);

        updateService.getLatestReleaseInfo()
                .thenCompose(releaseInfo -> {
                    if (releaseInfo == null) {
                        return inform(context, modId, "Failed to fetch latest release info", Formatting.RED);
                    }

                    var releaseVersion = releaseInfo.version();
                    if (releaseVersion.contains(modInfo.inferredVersion)) {
                        return inform(context, modId,
                                "Already up to date! (" + modInfo.inferredVersion + " ~= " + releaseVersion + ")",
                                Formatting.YELLOW);
                    }

                    inform(context, modId, "Attempting update from " + modInfo.inferredVersion + " -> " + releaseVersion, Formatting.YELLOW);
                    return updateService.downloadRelease(releaseInfo, modInfo.oldJarPath)
                            .thenCompose(success -> {
                                if (success) {
                                    return inform(context, modId, "Update successful! Restart to apply changes.", Formatting.GREEN);
                                } else {
                                    return inform(context, modId, "Failed to download release", Formatting.RED);
                                }
                            });
                })
                .exceptionally(throwable -> {
                    logger.error("Error updating mod {}", modId, throwable);
                    inform(context, modId, "Unexpected error during update", Formatting.RED);
                    return null;
                });

        return 1;
    }


    private static CompletableFuture<Void> inform(CommandContext<FabricClientCommandSource> context, String modId, String msg, Formatting colour) {
        var feedbackMessage = Text.literal(modId + ": " + msg).withColor(colour.getColorValue());

        CompletableFuture<Void> future = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            try {
                context.getSource().sendFeedback(feedbackMessage);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}
