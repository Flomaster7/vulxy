package me.cortex.voxy.client.core;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owns SPIR-V bytecode compiled from a Vulkan-compatible GLSL compute shader.
 *
 * Shader includes must be resolved by the caller before compilation. This is
 * deliberate: Voxy's existing OpenGL shader loader has its own include and
 * macro system, and silently ignoring it would compile the wrong program.
 *
 * The returned bytecode is a direct native-order buffer and remains valid
 * until this object is closed.
 */
public final class VoxyVulkanSpirv implements AutoCloseable {
    private static final Pattern DEFINE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final ByteBuffer bytecode;
    private final int warningCount;
    private boolean closed;

    private VoxyVulkanSpirv(ByteBuffer bytecode, int warningCount) {
        this.bytecode = bytecode;
        this.warningCount = warningCount;
    }

    /**
     * Compiles preprocessed GLSL compute source for Vulkan 1.2 / SPIR-V 1.5.
     *
     * @param source complete GLSL source with all includes already expanded
     * @param sourceName a useful source name included in compiler diagnostics
     */
    public static VoxyVulkanSpirv compileCompute(String source, String sourceName) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Compute shader source must not be empty");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Shader source name must not be empty");
        }

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("Shaderc could not initialize; check the LWJGL shaderc native library");
        }

        long options = 0L;
        long result = 0L;
        try {
            options = Shaderc.shaderc_compile_options_initialize();
            if (options == 0L) {
                throw new IllegalStateException("Shaderc could not create compile options");
            }

            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                    options,
                    Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2
            );
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_5);

            result = Shaderc.shaderc_compile_into_spv(
                    compiler,
                    source,
                    Shaderc.shaderc_glsl_compute_shader,
                    sourceName,
                    "main",
                    options
            );
            if (result == 0L) {
                throw new IllegalStateException("Shaderc returned a null compilation result for " + sourceName);
            }

            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                String diagnostics = Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalArgumentException("Vulkan compute shader compilation failed for "
                        + sourceName + " (status " + status + "): "
                        + (diagnostics == null ? "no compiler diagnostics" : diagnostics));
            }

            long byteLength = Shaderc.shaderc_result_get_length(result);
            if (byteLength <= 0 || byteLength > Integer.MAX_VALUE || (byteLength & 3L) != 0L) {
                throw new IllegalStateException("Shaderc returned an invalid SPIR-V byte length: " + byteLength);
            }

            ByteBuffer compiledBytes = Shaderc.shaderc_result_get_bytes(result, byteLength);
            if (compiledBytes == null || compiledBytes.remaining() < byteLength) {
                throw new IllegalStateException("Shaderc returned incomplete SPIR-V bytecode for " + sourceName);
            }

            ByteBuffer ownedBytes = MemoryUtil.memAlloc((int) byteLength).order(ByteOrder.nativeOrder());
            ownedBytes.put(compiledBytes.duplicate()).flip();
            int warnings = (int) Math.min(Integer.MAX_VALUE, Shaderc.shaderc_result_get_num_warnings(result));
            return new VoxyVulkanSpirv(ownedBytes, warnings);
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * Loads one of Voxy's existing shader resources, expands its #import tree
     * with the current ShaderLoader, applies compile-time defines/replacements,
     * and compiles the result for Vulkan.
     *
     * This is the bridge for Voxy's legacy shader assets: it preserves their
     * import structure while sending GLSL through Shaderc instead of OpenGL.
     * Only compute shaders that are already Vulkan-compatible can use it;
     * OpenGL-only extensions and binding assumptions still require migration.
     */
    public static VoxyVulkanSpirv compileVoxyCompute(String resourceId,
                                                       Map<String, String> defines,
                                                       Map<String, String> replacements) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Voxy shader resource id must not be empty");
        }

        String source = ShaderLoader.parse(resourceId);
        int firstLineEnd = source.indexOf('\\n');
        if (firstLineEnd < 0 || !source.startsWith("#version")) {
            throw new IllegalStateException("Voxy ShaderLoader did not return a GLSL source with a #version line: "
                    + resourceId);
        }

        StringBuilder expanded = new StringBuilder(source.length() + 256);
        expanded.append(source, 0, firstLineEnd + 1);
        if (defines != null) {
            for (Map.Entry<String, String> define : defines.entrySet()) {
                String name = define.getKey();
                String value = define.getValue();
                if (name == null || !DEFINE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid GLSL define name: " + name);
                }
                if (value == null || value.indexOf('\\n') >= 0 || value.indexOf('\\r') >= 0) {
                    throw new IllegalArgumentException("Invalid GLSL define value for " + name);
                }
                expanded.append("#define ").append(name).append(' ').append(value).append('\\n');
            }
        }
        expanded.append(source, firstLineEnd + 1, source.length());

        if (replacements != null) {
            for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                if (replacement.getKey() == null || replacement.getKey().isEmpty()
                        || replacement.getValue() == null) {
                    throw new IllegalArgumentException("Shader replacements must have non-empty keys and non-null values");
                }
                int index = expanded.indexOf(replacement.getKey());
                while (index >= 0) {
                    expanded.replace(index, index + replacement.getKey().length(), replacement.getValue());
                    index = expanded.indexOf(replacement.getKey(), index + replacement.getValue().length());
                }
            }
        }

        return compileCompute(expanded.toString(), resourceId);
    }

    /**
     * Returns a duplicate view of the owned SPIR-V data.
     */
    public ByteBuffer bytecode() {
        this.checkOpen();
        return this.bytecode.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public int byteLength() {
        this.checkOpen();
        return this.bytecode.remaining();
    }

    public int warningCount() {
        this.checkOpen();
        return this.warningCount;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MemoryUtil.memFree(this.bytecode);
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("SPIR-V bytecode has already been released");
        }
    }
}
