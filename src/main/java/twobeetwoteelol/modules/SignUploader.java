package twobeetwoteelol.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import twobeetwoteelol.Settings;
import twobeetwoteelol.api.Api;
import twobeetwoteelol.api.records.UploadResult;
import twobeetwoteelol.model.ExclusionZone;
import twobeetwoteelol.model.MinecraftSessionData;
import twobeetwoteelol.model.SignPayload;
import twobeetwoteelol.settings.ExcludedLocationsSetting;
import twobeetwoteelol.utils.Coordinates;
import twobeetwoteelol.utils.Is2b2t;
import twobeetwoteelol.utils.Messages;
import twobeetwoteelol.utils.StringyStringz;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SignUploader extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgExclusions = settings.createGroup("Excluded Locations");

    private final Setting<Integer> uploadIntervalSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("upload-interval-seconds")
        .description("How often to upload signs.")
        .defaultValue(30)
        .range(5, 120)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Boolean> verbose = sgGeneral.add(new BoolSetting.Builder()
        .name("verbose")
        .description("Send logs in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWithinSpawn = sgGeneral.add(new BoolSetting.Builder()
        .name("Spawn only")
        .description("Only upload signs within spawn")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> overworldSpawnRadius = sgGeneral.add(new IntSetting.Builder()
        .name("overworld-radius")
        .description("Distance from 0 0 in the overworld to upload from")
        .defaultValue(Settings.defaultOverworldSpawnRadius)
        .range(0, 30_000_000)
        .sliderRange(0, 500_000)
        .visible(onlyWithinSpawn::get)
        .build()
    );

    private final Setting<Integer> netherSpawnRadius = sgGeneral.add(new IntSetting.Builder()
        .name("nether-radius")
        .description("Distance from 00 in the nether to upload from")
        .defaultValue(Settings.defaultNetherSpawnRadius)
        .range(0, 30_000_000)
        .sliderRange(0, 500_000)
        .visible(onlyWithinSpawn::get)
        .build()
    );

    private final Setting<Integer> endSpawnRadius = sgGeneral.add(new IntSetting.Builder()
        .name("end-radius")
        .description("Distance from 00 in the end to upload from")
        .defaultValue(Settings.defaultEndSpawnRadius)
        .range(0, 30_000_000)
        .sliderRange(0, 500_000)
        .visible(onlyWithinSpawn::get)
        .build()
    );

    private final Setting<List<String>> excludedLocations = sgExclusions.add(new ExcludedLocationsSetting.Builder()
        .name("excluded-locations")
        .description(
            "Locations to never upload from"
        )
        .defaultRange(Settings.defaultExclusionRadius)
        .defaultValue()
        .build()
    );

    private final Api api = new Api(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    );
    private final Set<String> seenSigns = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, SignPayload> pendingSigns = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private Future<?> running;
    private volatile long nextUpload;
    private List<String> cachedExcludedLocations = List.of();
    private List<ExclusionZone> cachedExclusionZones = List.of();
    private ClientWorld lastWorld;

    public SignUploader() {
        super(
            Categories.Misc,
            "sign-uploader",
            "Uploads signs to 2b2t.lol"
        );
    }

    @Override
    public void onActivate() {
        api.resetSession();
        nextUpload = 0L;
        seenSigns.clear();
        pendingSigns.clear();
        cachedExcludedLocations = List.of();
        cachedExclusionZones = List.of();
        lastWorld = null;

        ensureExecutor();
        chatInfo("Enabled sign uploader");
    }

    @Override
    public void onDeactivate() {
        seenSigns.clear();
        pendingSigns.clear();
        lastWorld = null;
        shutdownExecutor();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            lastWorld = null;
            return;
        }

        if (!Is2b2t.is2b2t(mc)) {
            not2b2t();
            return;
        }

        if (mc.currentScreen instanceof AbstractSignEditScreen) {
            return;
        }

        if (mc.world != lastWorld) {
            lastWorld = mc.world;
        }

        getPendingSigns();

        if (running != null && !running.isDone()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextUpload) {
            return;
        }

        String baseUrl = Settings.defaultServerUrl;

        List<PendingSign> signs = snapshotPendingSigns();
        if (signs.isEmpty()) {
            nextUpload = now + (uploadIntervalSeconds.get() * 1000L);
            return;
        }

        nextUpload = now + (uploadIntervalSeconds.get() * 1000L);

        MinecraftSessionData.LoadResult sessionResult = MinecraftSessionData.from(mc.getSession());
        if (sessionResult.failed()) {
            Messages.chatError(mc, this::isActive, this::error, sessionResult.errorMessage());
            return;
        }

        MinecraftSessionData sessionData = sessionResult.value();

        ensureExecutor();
        running = executor.submit(() ->
            uploadSigns(
                baseUrl,
                sessionData.accessToken(),
                sessionData.username(),
                sessionData.uuid(),
                signs
            )
        );
    }

    private void uploadSigns(
        String baseUrl,
        String accessToken,
        String username,
        String uuid,
        List<PendingSign> signs
    ) {
        int limit = Settings.defaultMaxSigns;
        int totalUploaded = 0;
        int totalStored = 0;

        for (int start = 0; start < signs.size(); start += limit) {
            if (!isActive()) {
                return;
            }

            int end = Math.min(start + limit, signs.size());
            List<PendingSign> batch = signs.subList(start, end);
            List<SignPayload> payloadBatch = new ArrayList<>(batch.size());
            for (PendingSign pendingSign : batch) {
                payloadBatch.add(pendingSign.payload());
            }

            UploadResult result = api.uploadSigns(
                baseUrl,
                accessToken,
                username,
                uuid,
                payloadBatch,
                this::onAuthSuccess
            );
            if (!result.success()) {
                Messages.chatError(mc, this::isActive, this::error, "Upload failed %s", result.errorMessage());
                return;
            }

            totalUploaded += payloadBatch.size();
            totalStored += result.stored();
            for (PendingSign pendingSign : batch) {
                pendingSigns.remove(pendingSign.seenHash(), pendingSign.payload());
            }
        }

        chatInfo("Uploaded %d signs- Stored %d.", totalUploaded, totalStored);
    }

    private void onAuthSuccess() {
        if (verbose.get()) {
            chatInfo("Authed for sign upload.");
        }
    }

    private List<PendingSign> snapshotPendingSigns() {
        List<PendingSign> signs = new ArrayList<>(pendingSigns.size());
        pendingSigns.forEach((seenHash, sign) -> signs.add(new PendingSign(seenHash, sign)));
        return signs;
    }

    private void getPendingSigns() {
        String dimension = mc.world.getRegistryKey().getValue().toString().toLowerCase(Locale.ROOT);
        List<ExclusionZone> exclusionZones = getExclusionZones();
        int viewDistance = mc.options.getClampedViewDistance();
        int playerChunkX = mc.player.getBlockPos().getX() >> 4;
        int playerChunkZ = mc.player.getBlockPos().getZ() >> 4;

        for (int chunkX = playerChunkX - viewDistance; chunkX <= playerChunkX + viewDistance; chunkX++) {
            for (int chunkZ = playerChunkZ - viewDistance; chunkZ <= playerChunkZ + viewDistance; chunkZ++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
                if (chunk == null) {
                    continue;
                }

                getChunkSigns(chunk, dimension, exclusionZones);
            }
        }
    }

    private List<ExclusionZone> getExclusionZones() {
        List<String> currentExcludedLocations = excludedLocations.get();
        if (!currentExcludedLocations.equals(cachedExcludedLocations)) {
            cachedExcludedLocations = new ArrayList<>(currentExcludedLocations);
            cachedExclusionZones = StringyStringz.parseExclusionZones(
                cachedExcludedLocations,
                Settings.defaultExclusionRadius
            );
        }

        return cachedExclusionZones;
    }

    private void getChunkSigns(WorldChunk chunk, String dimension, List<ExclusionZone> exclusionZones) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!(blockEntity instanceof SignBlockEntity signBlockEntity)) {
                continue;
            }

            SignText frontText = signBlockEntity.getFrontText();
            String[] lines = StringyStringz.toLines(frontText.getMessages(false));
            if (!StringyStringz.hasContent(lines)) {
                continue;
            }

            BlockPos pos = signBlockEntity.getPos();
            if (Coordinates.isExcludedZone(exclusionZones, pos)) {
                continue;
            }

            if (!Coordinates.isWithinSpawnRadius(
                onlyWithinSpawn.get(),
                dimension,
                pos.getX(),
                pos.getZ(),
                overworldSpawnRadius.get(),
                netherSpawnRadius.get(),
                endSpawnRadius.get()
            )) {
                continue;
            }

            String signature = StringyStringz.buildSignature(lines);
            String seenHash = StringyStringz.seenHash(signature, dimension, pos);
            if (!seenSigns.add(seenHash)) {
                continue;
            }

            pendingSigns.put(seenHash, new SignPayload(
                dimension,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                lines
            ));
        }
    }

    private void ensureExecutor() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }

        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "2b2t-lol");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void shutdownExecutor() {
        if (running != null) {
            running.cancel(true);
            running = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void chatInfo(String message, Object... args) {
        if (!verbose.get()) {
            return;
        }

        mc.execute(() -> {
            if (isActive()) {
                info(message, args);
            }
        });
    }

    private void not2b2t() {
        error("This module only works on 2b2t. (disabled)");
        mc.inGameHud.setTitle(Text.literal("2b2t-lol disabled"));
        mc.inGameHud.setSubtitle(Text.literal("You can only use this on 2b2t."));
        mc.inGameHud.setTitleTicks(10, 80, 20);
        toggle();
    }

    private record PendingSign(String seenHash, SignPayload payload) {
    }

}
