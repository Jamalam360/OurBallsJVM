package io.github.jamalam360;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.BufferUtils.createByteBuffer;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.NVRayTracing.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * @author Jamalam360
 */
@SuppressWarnings({"SpellCheckingInspection", "unused"})
public class VKUtil {
    public static final int VK_FLAGS_NONE = 0;

    private static int vulkanStageToShadercKind(int stage) {
        return switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT -> shaderc_vertex_shader;
            case VK_SHADER_STAGE_FRAGMENT_BIT -> shaderc_fragment_shader;
            case VK_SHADER_STAGE_RAYGEN_BIT_NV -> shaderc_raygen_shader;
            case VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV -> shaderc_closesthit_shader;
            case VK_SHADER_STAGE_MISS_BIT_NV -> shaderc_miss_shader;
            case VK_SHADER_STAGE_ANY_HIT_BIT_NV -> shaderc_anyhit_shader;
            default -> throw new IllegalArgumentException("Stage: " + stage);
        };
    }

    public static ByteBuffer glslToSpirv(String classPath, int vulkanStage) throws IOException {
        ByteBuffer src = ioResourceToByteBuffer(classPath, 1024);
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        ShadercIncludeResolve resolver;
        ShadercIncludeResultRelease releaser;
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
        shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_0);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
        shaderc_compile_options_set_include_callbacks(options, resolver = new ShadercIncludeResolve() {
            public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
                ShadercIncludeResult res = ShadercIncludeResult.calloc();
                try {
                    String src = classPath.substring(0, classPath.lastIndexOf('/')) + "/" + memUTF8(requested_source);
                    res.content(ioResourceToByteBuffer(src, 1024));
                    res.source_name(memUTF8(src));
                    return res.address();
                } catch (IOException e) {
                    throw new AssertionError("Failed to resolve include: " + src);
                }
            }
        }, releaser = new ShadercIncludeResultRelease() {
            public void invoke(long user_data, long include_result) {
                ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
                memFree(result.source_name());
                result.free();
            }
        }, 0L);
        long res;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            res = shaderc_compile_into_spv(compiler, src, vulkanStageToShadercKind(vulkanStage), stack.UTF8(classPath), stack.UTF8("main"), options);
            if (res == 0L)
                throw new AssertionError("Internal error during compilation!");
        }
        if (shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success) {
            throw new AssertionError("Shader compilation failed: " + shaderc_result_get_error_message(res));
        }
        int size = (int) shaderc_result_get_length(res);
        ByteBuffer resultBytes = createByteBuffer(size);
        resultBytes.put(shaderc_result_get_bytes(res));
        resultBytes.flip();
        shaderc_result_release(res);
        shaderc_compiler_release(compiler);
        releaser.free();
        resolver.free();
        return resultBytes;
    }

    public static void _CHECK_(int ret, String msg) {
        if (ret != VK_SUCCESS)
            throw new AssertionError(msg + ": " + translateVulkanResult(ret));
    }

    public static void loadShader(VkPipelineShaderStageCreateInfo info, VkSpecializationInfo specInfo, MemoryStack stack, VkDevice device, String classPath,
                                  int stage) throws IOException {
        ByteBuffer shaderCode = glslToSpirv(classPath, stage);
        LongBuffer pShaderModule = stack.mallocLong(1);
        _CHECK_(vkCreateShaderModule(device, VkShaderModuleCreateInfo(stack).pCode(shaderCode).flags(0), null, pShaderModule),
                "Failed to create shader module");
        info.stage(stage).pSpecializationInfo(specInfo).module(pShaderModule.get(0)).pName(stack.UTF8("main"));
    }

    /**
     * Translates a Vulkan {@code VkResult} value to a String describing the result.
     *
     * @param result the {@code VkResult} value
     * @return the result description
     */
    public static String translateVulkanResult(int result) {
        return switch (result) {
            // Success codes
            case VK_SUCCESS -> "Command successfully completed.";
            case VK_NOT_READY -> "A fence or query has not yet completed.";
            case VK_TIMEOUT -> "A wait operation has not completed in the specified time.";
            case VK_EVENT_SET -> "An event is signaled.";
            case VK_EVENT_RESET -> "An event is unsignaled.";
            case VK_INCOMPLETE -> "A return array was too small for the result.";
            case VK_SUBOPTIMAL_KHR -> "A swapchain no longer matches the surface properties exactly, but can still be used to present to the surface successfully.";

            // Error codes
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "A host memory allocation has failed.";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "A device memory allocation has failed.";
            case VK_ERROR_INITIALIZATION_FAILED -> "Initialization of an object could not be completed for implementation-specific reasons.";
            case VK_ERROR_DEVICE_LOST -> "The logical or physical device has been lost.";
            case VK_ERROR_MEMORY_MAP_FAILED -> "Mapping of a memory object has failed.";
            case VK_ERROR_LAYER_NOT_PRESENT -> "A requested layer is not present or could not be loaded.";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "A requested extension is not supported.";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "A requested feature is not supported.";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "The requested version of Vulkan is not supported by the driver or is otherwise incompatible for implementation-specific reasons.";
            case VK_ERROR_TOO_MANY_OBJECTS -> "Too many objects of the type have already been created.";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "A requested format is not supported on this device.";
            case VK_ERROR_SURFACE_LOST_KHR -> "A surface is no longer available.";
            case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR -> "The requested window is already connected to a VkSurfaceKHR, or to some other non-Vulkan API.";
            case VK_ERROR_OUT_OF_DATE_KHR -> "A surface has changed in such a way that it is no longer compatible with the swapchain, and further presentation requests using the "
                    + "swapchain will fail. Applications must query the new surface properties and recreate their swapchain if they wish to continue"
                    + "presenting to the surface.";
            case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR -> "The display used by a swapchain does not use the same presentable image layout, or is incompatible in a way that prevents sharing an"
                    + " image.";
            case VK_ERROR_VALIDATION_FAILED_EXT -> "A validation layer found an error.";
            default -> String.format("%s [%d]", "Unknown", result);
        };
    }

    public static PointerBuffer allocateLayerBuffer(String[] layers) {
        final Set<String> availableLayers = getAvailableLayers();

        PointerBuffer ppEnabledLayerNames = memAllocPointer(layers.length);
        System.out.println("Using layers:");
        for (final String layer : layers) {
            if (availableLayers.contains(layer)) {
                System.out.println("\t" + layer);
                ppEnabledLayerNames.put(memUTF8(layer));
            }
        }
        ppEnabledLayerNames.flip();
        return ppEnabledLayerNames;
    }

    private static Set<String> getAvailableLayers() {
        final Set<String> res = new HashSet<>();
        final int[] ip = new int[1];
        vkEnumerateInstanceLayerProperties(ip, null);
        final int count = ip[0];

        try (final MemoryStack stack = MemoryStack.stackPush()) {
            if (count > 0) {
                final VkLayerProperties.Buffer instanceLayers = VkLayerProperties.mallocStack(count, stack);
                vkEnumerateInstanceLayerProperties(ip, instanceLayers);
                for (int i = 0; i < count; i++) {
                    final String layerName = instanceLayers.get(i).layerNameString();
                    res.add(layerName);
                }
            }
        }

        return res;
    }

    static VkShaderModuleCreateInfo VkShaderModuleCreateInfo(MemoryStack stack) {
        return VkShaderModuleCreateInfo.callocStack(stack).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
    }

    public static ByteBuffer ioResourceToByteBuffer(String resource, int bufferSize) throws IOException {
        ByteBuffer buffer;
        URL url = Thread.currentThread().getContextClassLoader().getResource(resource);
        if (url == null)
            throw new IOException("Classpath resource not found: " + resource);
        File file = new File(url.getFile());
        if (file.isFile()) {
            FileInputStream fis = new FileInputStream(file);
            FileChannel fc = fis.getChannel();
            buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            fc.close();
            fis.close();
        } else {
            buffer = BufferUtils.createByteBuffer(bufferSize);
            InputStream source = url.openStream();
            try (source) {
                if (source == null)
                    throw new FileNotFoundException(resource);
                byte[] buf = new byte[8192];
                while (true) {
                    int bytes = source.read(buf, 0, buf.length);
                    if (bytes == -1)
                        break;
                    if (buffer.remaining() < bytes)
                        buffer = resizeBuffer(buffer, Math.max(buffer.capacity() * 2, buffer.capacity() - buffer.remaining() + bytes));
                    buffer.put(buf, 0, bytes);
                }
                buffer.flip();
            }
        }
        return buffer;
    }

    private static ByteBuffer resizeBuffer(ByteBuffer buffer, int newCapacity) {
        ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }
}
