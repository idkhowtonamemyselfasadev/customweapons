package dev.cwshot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test-only client automation: joins the server named in -Dcw.server, then runs the
 * script in -Dcw.script one line per client tick step. Lines:
 *
 *   wait N          idle N ticks
 *   cmd TEXT        send a command (no leading slash) or, with "say:" prefix, chat
 *   look YAW PITCH  set the view
 *   hotbar N        select hotbar slot N (0-8)
 *   attack          one left click
 *   use             one right click
 *   hold N          hold right click for N ticks, then release (bows, tridents, crossbows)
 *   camera first|back|front
 *   shot NAME       screenshot to run/screenshots/NAME.png
 *   quit            leave
 */
public final class ShotRig implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("cwshot");
    private final List<String> lines = new ArrayList<>();
    private int index;
    private int wait;
    private int holdLeft;
    private int ticks;
    private boolean connecting;

    @Override
    public void onInitializeClient() {
        String script = System.getProperty("cw.script");
        if (script != null) {
            try {
                for (String line : Files.readAllLines(Path.of(script))) {
                    String s = line.strip();
                    if (!s.isEmpty() && !s.startsWith("#")) {
                        lines.add(s);
                    }
                }
            } catch (Exception e) {
                LOG.error("cannot read script {}: {}", script, e.toString());
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("ShotRig ready: {} script lines", lines.size());
    }

    private void tick(Minecraft mc) {
        ticks++;
        String server = System.getProperty("cw.server");
        if (server != null && !connecting && mc.level == null && ticks > 60 && mc.screen instanceof TitleScreen) {
            connecting = true;
            LOG.info("connecting to {}", server);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server),
                    new ServerData("shotrig", server, ServerData.Type.OTHER), false, null);
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (holdLeft > 0) {
            // Held in the background: the script keeps running, so a screenshot can be
            // taken mid-draw. Released when the count runs out.
            mc.options.keyUse.setDown(true);
            if (--holdLeft == 0) {
                mc.options.keyUse.setDown(false);
            }
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (index >= lines.size()) {
            return;
        }
        String line = lines.get(index++);
        String[] a = line.split("\\s+", 2);
        String arg = a.length > 1 ? a[1] : "";
        LOG.info("script: {}", line);
        try {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "wait" -> wait = Integer.parseInt(arg);
                case "cmd" -> {
                    if (arg.startsWith("say:")) {
                        mc.player.connection.sendChat(arg.substring(4));
                    } else {
                        mc.player.connection.sendCommand(arg);
                    }
                    wait = 3;   // a breath between commands, for the server's spam counter
                }
                case "look" -> {
                    String[] p = arg.split("\\s+");
                    float yaw = Float.parseFloat(p[0]), pitch = Float.parseFloat(p[1]);
                    mc.player.setYRot(yaw);
                    mc.player.setXRot(pitch);
                    mc.player.yRotO = yaw;
                    mc.player.xRotO = pitch;
                    mc.player.setYHeadRot(yaw);
                    mc.player.yBodyRot = yaw;
                }
                case "hotbar" -> mc.player.getInventory().setSelectedSlot(Integer.parseInt(arg));
                case "attack" -> KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT));
                case "use" -> KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
                case "hold" -> {
                    KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
                    mc.options.keyUse.setDown(true);
                    holdLeft = Integer.parseInt(arg);
                }
                case "camera" -> mc.options.setCameraType(switch (arg) {
                    case "back" -> CameraType.THIRD_PERSON_BACK;
                    case "front" -> CameraType.THIRD_PERSON_FRONT;
                    default -> CameraType.FIRST_PERSON;
                });
                case "shot" -> Screenshot.grab(mc.gameDirectory, arg + ".png", mc.getMainRenderTarget(), 1,
                        m -> LOG.info("screenshot {}", m.getString()));
                case "quit" -> mc.stop();
                default -> LOG.warn("unknown script line: {}", line);
            }
        } catch (Exception e) {
            LOG.error("script line failed: {} ({})", line, e.toString());
        }
    }
}
