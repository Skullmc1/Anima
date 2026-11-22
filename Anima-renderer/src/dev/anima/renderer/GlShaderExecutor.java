package dev.anima.renderer;

import dev.anima.core.AnimaConfig;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Real OpenGL 3.3 Core GPU shader compiler & PostChain executor for Anima-renderer.
 *
 * Unlike a CPU approximation, this class compiles and executes the ACTUAL GLSL shader files
 * from Anima-mod/src/client/resources/resourcepacks/anima_shaders/ directly on the GPU:
 *  - Validates both Vanilla 26.2 (terrain.vsh/.fsh) and Sodium 0.9.2 (block_layer_opaque.vsh/.fsh)
 *  - Executes real PostChain shaders (lush_bloom + lush, borderlands, color_pencil, blueprint)
 *    against an exact Minecraft 26.2 Reverse-Z depth buffer (d = 0.05 / Z, sky = 0.0).
 */
public final class GlShaderExecutor implements AutoCloseable {

    private static final Path SHADER_ROOT = Path.of("..", "Anima-mod", "src", "client", "resources", "resourcepacks", "anima_shaders", "assets");

    private static final String FULLSCREEN_QUAD_VSH = """
            #version 330 core
            out vec2 texCoord;
            void main() {
                vec2 pos = vec2((gl_VertexID & 1) * 2.0, (gl_VertexID & 2) * 1.0);
                // Fullscreen triangle covering [-1, 1] with UV in [0, 1]
                texCoord = pos;
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private long window;
    private int vao;
    private int uboSamplerInfo;
    private final Map<String, Integer> postPrograms = new HashMap<>();

    public GlShaderExecutor() {
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW for headless OpenGL");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        window = GLFW.glfwCreateWindow(64, 64, "Anima-GPU-Validator", 0L, 0L);
        if (window == 0L) {
            GLFW.glfwTerminate();
            throw new IllegalStateException("Failed to create headless OpenGL 3.3 context");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        vao = GL30.glGenVertexArrays();
        uboSamplerInfo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboSamplerInfo);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, 16L, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

        System.out.println("[Anima-GPU] Initialized real OpenGL " + GL11.glGetString(GL11.GL_VERSION)
                + " on " + GL11.glGetString(GL11.GL_RENDERER));

        compileAllPostShaders();
    }

    /**
     * Validates that Anima-mod's actual Vanilla (terrain.vsh/.fsh) and Sodium (block_layer_opaque.vsh/.fsh)
     * GLSL shaders compile cleanly on a real OpenGL 3.3 driver.
     */
    public void validateClientShaders(Path mcJar, Path sodiumJar) {
        if (mcJar != null && Files.exists(mcJar)) {
            boolean vOk = tryCompileWithMojImports(
                    SHADER_ROOT.resolve("minecraft/shaders/core/terrain.vsh"),
                    GL20.GL_VERTEX_SHADER, mcJar, null, "#define ALPHA_CUTOUT 0.1\n");
            boolean fOk = tryCompileWithMojImports(
                    SHADER_ROOT.resolve("minecraft/shaders/core/terrain.fsh"),
                    GL20.GL_FRAGMENT_SHADER, mcJar, null, "#define ALPHA_CUTOUT 0.1\n");
            System.out.println("[Anima-GPU] Vanilla 26.2 terrain.vsh/.fsh GPU compile: " + (vOk && fOk ? "PASS" : "FAIL"));
        }
        if (sodiumJar != null && Files.exists(sodiumJar)) {
            String sodDefs = "#define USE_FOG\n#define USE_FOG_SMOOTH\n#define USE_VERTEX_COMPRESSION\n#define VERT_POS_SCALE 0.000030517578125\n#define VERT_POS_OFFSET -8.0\n#define VERT_TEX_SCALE 0.000030517578125\n#define ALPHA_CUTOUT 0.1\n";
            boolean svOk = tryCompileWithMojImports(
                    SHADER_ROOT.resolve("sodium/shaders/blocks/block_layer_opaque.vsh"),
                    GL20.GL_VERTEX_SHADER, mcJar, sodiumJar, sodDefs);
            boolean sfOk = tryCompileWithMojImports(
                    SHADER_ROOT.resolve("sodium/shaders/blocks/block_layer_opaque.fsh"),
                    GL20.GL_FRAGMENT_SHADER, mcJar, sodiumJar, sodDefs);
            System.out.println("[Anima-GPU] Sodium 0.9.2 block_layer_opaque.vsh/.fsh GPU compile: " + (svOk && sfOk ? "PASS" : "FAIL"));
        }
    }

    private void compileAllPostShaders() {
        String[] names = {"lush_bloom", "lush_final", "borderlands", "color_pencil", "blueprint"};
        for (String name : names) {
            Path fshPath = SHADER_ROOT.resolve("anima/shaders/post/" + name + ".fsh");
            try {
                String fshSrc = Files.readString(fshPath, StandardCharsets.UTF_8);
                int prog = linkProgram(FULLSCREEN_QUAD_VSH, fshSrc, name + ".fsh");
                postPrograms.put(name, prog);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load post shader: " + fshPath, e);
            }
        }
        System.out.println("[Anima-GPU] Compiled & linked all 5 real Anima PostChain GLSL shaders on GPU.");
    }

    /**
     * Executes Anima-mod's ACTUAL PostChain GLSL shader(s) on the GPU for the given preset,
     * using an exact Minecraft 26.2 Reverse-Z depth texture (d = 0.05 / Z, sky = 0.0).
     */
    public void applyRealPostChain(BufferedImage img, float[] worldDistBuf, AnimaConfig config) {
        if (!config.isActive()) {
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();

        int inColorTex = uploadImageTexture(img);
        int inDepthTex = uploadReverseZDepthTexture(worldDistBuf, w, h);

        if (config.preset == AnimaConfig.Preset.LUSH) {
            // Exact 2-pass PostChain matching lush.json: lush_bloom -> lush_final
            int bloomTex = createColorTexture(w, h);
            int outTex = createColorTexture(w, h);
            runPostPass(postPrograms.get("lush_bloom"), inColorTex, 0, bloomTex, w, h, false);
            runPostPass(postPrograms.get("lush_final"), inColorTex, bloomTex, outTex, w, h, true);
            readBackTextureToImage(outTex, img);
            GL11.glDeleteTextures(bloomTex);
            GL11.glDeleteTextures(outTex);
        } else {
            String key = switch (config.preset) {
                case BORDERLANDS -> "borderlands";
                case COLOR_PENCIL -> "color_pencil";
                case BLUEPRINT -> "blueprint";
                default -> null;
            };
            if (key != null) {
                int outTex = createColorTexture(w, h);
                runPostPass(postPrograms.get(key), inColorTex, inDepthTex, outTex, w, h, false);
                readBackTextureToImage(outTex, img);
                GL11.glDeleteTextures(outTex);
            }
        }

        GL11.glDeleteTextures(inColorTex);
        GL11.glDeleteTextures(inDepthTex);
    }

    private void runPostPass(int program, int texUnit0, int texUnit1, int outColorTex, int w, int h, boolean secondIsBloom) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outColorTex, 0);

        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);

        // Bind SamplerInfo std140 UBO: vec2 OutSize; vec2 InSize;
        FloatBuffer uboBuf = BufferUtils.createFloatBuffer(4);
        uboBuf.put(w).put(h).put(w).put(h).flip();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboSamplerInfo);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, uboBuf);
        int blockIdx = GL31.glGetUniformBlockIndex(program, "SamplerInfo");
        if (blockIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, blockIdx, 0);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, uboSamplerInfo);
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texUnit0);
        int locIn = GL20.glGetUniformLocation(program, "InSampler");
        if (locIn >= 0) GL20.glUniform1i(locIn, 0);

        if (texUnit1 != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texUnit1);
            String secondName = secondIsBloom ? "BloomSampler" : "InDepthSampler";
            int loc2 = GL20.glGetUniformLocation(program, secondName);
            if (loc2 >= 0) GL20.glUniform1i(loc2, 1);
        }

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(fbo);
    }

    private static int uploadImageTexture(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        // Flip Y for OpenGL bottom-left UV origin (identical to Minecraft's mainRenderTarget!)
        for (int y = h - 1; y >= 0; y--) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = argb[row + x];
                buf.put((byte) ((p >> 16) & 0xFF));
                buf.put((byte) ((p >> 8) & 0xFF));
                buf.put((byte) (p & 0xFF));
                buf.put((byte) 0xFF);
            }
        }
        buf.flip();
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return tex;
    }

    /**
     * Uploads an EXACT Minecraft 26.2 Reverse-Z depth texture:
     *   sky (worldDist >= 250) -> 0.0f
     *   geometry at distance Z -> 0.05f / max(0.05f, Z)
     */
    private static int uploadReverseZDepthTexture(float[] worldDistBuf, int w, int h) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(w * h);
        for (int y = h - 1; y >= 0; y--) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float z = worldDistBuf[row + x];
                float revZ = (z >= 250.0f) ? 0.0f : (0.05f / Math.max(0.05f, z));
                buf.put(revZ);
            }
        }
        buf.flip();
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, w, h, 0, GL11.GL_RED, GL11.GL_FLOAT, buf);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static int createColorTexture(int w, int h) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static void readBackTextureToImage(int tex, BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(fbo);

        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            int srcRow = (h - 1 - y) * w * 4;
            int dstRow = y * w;
            for (int x = 0; x < w; x++) {
                int i = srcRow + x * 4;
                int r = buf.get(i) & 0xFF;
                int g = buf.get(i + 1) & 0xFF;
                int b = buf.get(i + 2) & 0xFF;
                dst[dstRow + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        img.setRGB(0, 0, w, h, dst, 0, w);
    }

    private static int linkProgram(String vshSrc, String fshSrc, String label) {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, vshSrc, label + " [VS]");
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, fshSrc, label + " [FS]");
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("GLSL link failed for " + label + ":\n" + GL20.glGetProgramInfoLog(prog));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return prog;
    }

    private static int compileShader(int type, String src, String label) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("GLSL compile failed for " + label + ":\n" + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private static boolean tryCompileWithMojImports(Path shaderFile, int type, Path mcJar, Path sodiumJar, String defines) {
        try {
            String raw = Files.readString(shaderFile, StandardCharsets.UTF_8);
            String expanded = expandMojImports(raw, mcJar, sodiumJar, defines);
            int s = GL20.glCreateShader(type);
            GL20.glShaderSource(s, expanded);
            GL20.glCompileShader(s);
            boolean ok = GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE;
            if (!ok) {
                System.err.println("[Anima-GPU] Shader compile error in " + shaderFile.getFileName() + ":\n" + GL20.glGetShaderInfoLog(s));
            }
            GL20.glDeleteShader(s);
            return ok;
        } catch (Exception e) {
            System.err.println("[Anima-GPU] Failed to validate " + shaderFile.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    private static String expandMojImports(String src, Path mcJar, Path sodiumJar, String defines) throws IOException {
        return expandMojImports(src, mcJar, sodiumJar, defines, true);
    }

    private static String expandMojImports(String src, Path mcJar, Path sodiumJar, String defines, boolean isRoot) throws IOException {
        StringBuilder out = new StringBuilder();
        Pattern p = Pattern.compile("^\\s*#moj_import\\s+<([^:>]+):([^>]+)>\\s*$");
        boolean insertedDefines = false;
        for (String line : src.split("\\R")) {
            if (line.trim().startsWith("#version")) {
                if (isRoot) {
                    out.append(line).append('\n');
                    if (!insertedDefines && defines != null && !defines.isEmpty()) {
                        out.append(defines);
                        insertedDefines = true;
                    }
                }
                continue;
            }
            Matcher m = p.matcher(line);
            if (m.matches()) {
                String ns = m.group(1);
                String inc = m.group(2);
                Path jar = "sodium".equals(ns) ? sodiumJar : mcJar;
                String entryPath = "assets/" + ns + "/shaders/include/" + inc;
                String incSrc = readZipText(jar, entryPath);
                if (incSrc == null && "sodium".equals(ns)) {
                    incSrc = readZipText(jar, "assets/sodium/shaders/" + inc);
                }
                if (incSrc != null) {
                    out.append(expandMojImports(incSrc, mcJar, sodiumJar, "", false)).append('\n');
                }
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String readZipText(Path jarPath, String entryName) throws IOException {
        if (jarPath == null || !Files.exists(jarPath)) return null;
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            ZipEntry e = zf.getEntry(entryName);
            if (e == null) return null;
            return new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        for (int prog : postPrograms.values()) {
            GL20.glDeleteProgram(prog);
        }
        postPrograms.clear();
        if (uboSamplerInfo != 0) GL15.glDeleteBuffers(uboSamplerInfo);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (window != 0L) {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            window = 0L;
        }
    }
}
