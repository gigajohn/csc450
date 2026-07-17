package com.jlathrop.engine.graphics.vulkan;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import com.jlathrop.engine.core.Window;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwWaitEvents;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetPhysicalDevicePresentationSupport;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackFloats;
import static org.lwjgl.system.MemoryStack.stackMallocPointer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

import static org.lwjgl.vulkan.KHRSwapchain.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;




public class VulkanContext {
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    
    private VkQueue graphicsQueue;
    private int graphicsQueueFamilyIndex = -1;
    
    private VkQueue presentQueue;
    private int presentQueueFamilyIndex = -1;

    private long swapchain;

    private List<Long> swapchainImages;
    private List<Long> swapchainImageViews;

    private long renderPass;

    private long pipelineLayout;
    private long graphicsPipeline;

    private static final boolean ENABLE_VALIDATION_LAYERS = false;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private List<Long> swapchainFramebuffers;

    private long commandPool;
    private List<Long> commandBuffers;

    private long windowHandle;
    private int swapchainImageFormat;
    private VkExtent2D swapchainExtent;

    private static final int MAX_FRAMES_IN_FLIGHT = 2;
    private int currentFrame = 0;

    private List<Long> imageAvailableSemaphores;
    private List<Long> renderFinishedSemaphores;
    private List<Long> inFlightFences;

    // Define 4 unique vertices (Position X, Y, Color R, G, B)
    private static final float[] VERTICES = {
        -0.5f, -0.5f, 1.0f, 0.0f, 0.0f, // Top left (Red)
        0.5f, -0.5f, 0.0f, 1.0f, 0.0f, // Top right (Green)
        0.5f,  0.5f, 0.0f, 0.0f, 1.0f, // Bottom right (Blue)
        -0.5f,  0.5f, 1.0f, 1.0f, 1.0f  // Bottom left (White)
    };

    // Define the 2 triangles using indices (pointing to the vertices above)
    private static final short[] INDICES = {
        0, 1, 2, 2, 3, 0
    };

    private long indexBuffer;
    private long indexBufferMemory;

    private long vertexBuffer;
    private long vertexBufferMemory;

    private long descriptorSetLayout;

    private List<Long> uniformBuffers;
    private List<Long> uniformBuffersMemory;
    private List<Long> uniformBuffersMapped;

    private long descriptorPool;
    private List<Long> descriptorSets;

    // To track time for the rotation animation
    private long startTime = System.nanoTime();

    public void drawFrame(Window window) {
        try (MemoryStack stack = stackPush()) {
            // 1. Get the sync objects for the current frame
            long currentInFlightFence = inFlightFences.get(currentFrame);
            long currentImageAvailableSemaphore = imageAvailableSemaphores.get(currentFrame);
            long currentRenderFinishedSemaphore = renderFinishedSemaphores.get(currentFrame);

            // 2. Wait for this specific frame's fence
            vkWaitForFences(device, stack.longs(currentInFlightFence), true, Long.MAX_VALUE);
            vkResetFences(device, stack.longs(currentInFlightFence));

            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireResult = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, currentImageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);

           if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain(window);
                return; // Try again next frame
            } else if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
                throw new RuntimeException("Failed to acquire next swapchain image: " + acquireResult);
            }

            int imageIndex = pImageIndex.get(0);
            updateUniformBuffer(imageIndex);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pWaitSemaphores(stack.longs(currentImageAvailableSemaphore));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            submitInfo.pCommandBuffers(stack.pointers(commandBuffers.get(imageIndex)));
            submitInfo.pSignalSemaphores(stack.longs(currentRenderFinishedSemaphore));

            if (vkQueueSubmit(graphicsQueue, submitInfo, currentInFlightFence) != VK_SUCCESS) {
                throw new RuntimeException("Failed to submit draw command buffer!");
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(stack.longs(currentRenderFinishedSemaphore));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchain));
            presentInfo.pImageIndices(pImageIndex);

            int presentResult = vkQueuePresentKHR(presentQueue, presentInfo);
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR || window.isFramebufferResized()) {
                window.setFramebufferResized(false); // Reset the flag
                recreateSwapchain(window);
            } else if (presentResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present swapchain image: " + presentResult);
            }

            // 3. Advance to the next frame
            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        }
    }

    public void init(long windowHandle) throws IOException{
        this.windowHandle = windowHandle;
        createInstance();
        createSurface(windowHandle);
        pickPhysicalDevice();
        createLogicalDevice();
        createSwapchain();
        createImageViews();
        createRenderPass();
        createDescriptorSetLayout();
        createGraphicsPipeline(renderPass);
        createFramebuffers();
        createCommandPool();
        createVertexBuffer();
        createIndexBuffer();
        createCommandBuffers();
        createUniformBuffers();
        createDescriptorPool();
        createDescriptorSets();
        recordCommandBuffers();
        createSyncObjects();
    }

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8("Vulkan Engine"));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8("No Engine"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.apiVersion(VK_API_VERSION_1_0);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);

            PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                throw new RuntimeException("Failed to find required GLFW Vulkan extensions!");
            }

            if (ENABLE_VALIDATION_LAYERS) {
                PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);
                extensions.put(glfwExtensions);
                extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
                extensions.flip();
                createInfo.ppEnabledExtensionNames(extensions);

                PointerBuffer layers = stack.mallocPointer(1);
                layers.put(stack.UTF8(VALIDATION_LAYER));
                layers.flip();
                createInfo.ppEnabledLayerNames(layers);
            } else {
                createInfo.ppEnabledExtensionNames(glfwExtensions);
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan instance. Vulkan error " + result);
            }

            instance = new VkInstance(pInstance.get(0), createInfo);
        }
    }

    private void createSurface(long windowHandle) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            if (glfwCreateWindowSurface(instance, windowHandle, null, pSurface) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Window Surface!");
            }
            surface = pSurface.get(0);
            System.out.println("Window Surface successfully created!");
        }
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer deviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("Failed to find GPUs with Vulkan support!");
            }

            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, devices);

            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
                if (isDeviceSuitable(device)) {
                    physicalDevice = device;
                    break;
                }
            }

            if (physicalDevice == null) {
                throw new RuntimeException("Failed to find a suitable GPU!");
            }
        }
    }

    private boolean isDeviceSuitable(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device, deviceProperties);

            String deviceName = deviceProperties.deviceNameString();
            int deviceType = deviceProperties.deviceType();

            System.out.println("Checking GPU: " + deviceName);

            boolean isHardwareGpu = deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU || 
                                    deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;

            if (isHardwareGpu) {
                System.out.println("-> Selected GPU: " + deviceName);
                return true;
            }
            return false;
        }
    }

    private void findQueueFamilies(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer queueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            for (int i = 0; i < queueFamilies.capacity(); i++) {
                if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    graphicsQueueFamilyIndex = i;
                }

                if (glfwGetPhysicalDevicePresentationSupport(instance, device, i)) {
                    presentQueueFamilyIndex = i;
                }

                if (graphicsQueueFamilyIndex != -1 && presentQueueFamilyIndex != -1) {
                    break;
                }
            }

            if (graphicsQueueFamilyIndex == -1 || presentQueueFamilyIndex == -1) {
                throw new RuntimeException("Failed to find suitable queue families for graphics and presentation!");
            }
        }
    }

    private void createLogicalDevice() {
        findQueueFamilies(physicalDevice);

        try (MemoryStack stack = stackPush()) {
            Set<Integer> uniqueQueueFamilies = new HashSet<>();
            uniqueQueueFamilies.add(graphicsQueueFamilyIndex);
            uniqueQueueFamilies.add(presentQueueFamilyIndex);

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.size(), stack);

            int i = 0;
            for (Integer queueFamily : uniqueQueueFamilies) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i++);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(queueFamily);
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f));
            }

            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures);

            PointerBuffer deviceExtensions = stack.mallocPointer(1);
            deviceExtensions.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            deviceExtensions.flip();
            createInfo.ppEnabledExtensionNames(deviceExtensions);

            PointerBuffer pDevice = stack.mallocPointer(1);
            if (vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create logical device");
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsQueueFamilyIndex, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);

            vkGetDeviceQueue(device, presentQueueFamilyIndex, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);
            
            System.out.println("Logical Device, Graphics Queue, and Present Queue successfully created!");
        }
    }

    private void createSwapchain() {
    try(MemoryStack stack = stackPush()) {
        // 1. Query Supported Formats
        IntBuffer formatCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null);
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(formatCount.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, formats);

        swapchainImageFormat = formats.get(0).format(); // Default fallback
        int colorSpace = formats.get(0).colorSpace();
        
        for (int i = 0; i < formats.capacity(); i++) {
            if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_SRGB && formats.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                swapchainImageFormat = VK_FORMAT_B8G8R8A8_SRGB;
                colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                break;
            }
        }

        //Query Capabilities and Extent
        VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);

        IntBuffer widthBuf = stack.mallocInt(1);
        IntBuffer heightBuf = stack.mallocInt(1);
        glfwGetFramebufferSize(windowHandle, widthBuf, heightBuf);

        swapchainExtent = VkExtent2D.create().set(
            Math.max(capabilities.minImageExtent().width(), Math.min(capabilities.maxImageExtent().width(), widthBuf.get(0))),
            Math.max(capabilities.minImageExtent().height(), Math.min(capabilities.maxImageExtent().height(), heightBuf.get(0)))
        );

        int imageCount = capabilities.minImageCount() + 1;
        if (capabilities.maxImageCount() > 0 && imageCount > capabilities.maxImageCount()) {
            imageCount = capabilities.maxImageCount();
        }

        //Create Swapchain Info
        VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);
        createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
        createInfo.surface(surface);
        createInfo.minImageCount(imageCount);
        createInfo.imageFormat(swapchainImageFormat);
        createInfo.imageColorSpace(colorSpace);
        createInfo.imageExtent(swapchainExtent);
        createInfo.imageArrayLayers(1);
        createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

        if (graphicsQueueFamilyIndex != presentQueueFamilyIndex) {
            createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
            createInfo.pQueueFamilyIndices(stack.ints(graphicsQueueFamilyIndex, presentQueueFamilyIndex));
        } else {
            createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
        }

        createInfo.preTransform(capabilities.currentTransform());
        createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
        createInfo.presentMode(VK_PRESENT_MODE_FIFO_KHR);
        createInfo.clipped(true);
        createInfo.oldSwapchain(VK_NULL_HANDLE);

        LongBuffer pSwapchain = stack.mallocLong(1);
        if(vkCreateSwapchainKHR(device, createInfo, null, pSwapchain) != VK_SUCCESS)
            throw new RuntimeException("Failed to create swapchain");

        swapchain = pSwapchain.get(0);
    }
}

    private void createImageViews(){
        try(MemoryStack stack = stackPush()){
            IntBuffer imageCount = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(device, swapchain, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, imageCount, pSwapchainImages);

            swapchainImages = new ArrayList<>(imageCount.get(0));
            for(int i = 0; i< pSwapchainImages.capacity();i++)
                swapchainImages.add(pSwapchainImages.get(i));

            swapchainImageViews = new ArrayList<>(swapchainImages.size());

            for(Long swapcahinImage: swapchainImages){
                VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                createInfo.image(swapcahinImage);

                //treat image as a 2d texture
                createInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
                //muust match format created in swapchain
                createInfo.format(swapchainImageFormat);

                createInfo.components().r(VK_COMPONENT_SWIZZLE_IDENTITY);
                createInfo.components().g(VK_COMPONENT_SWIZZLE_IDENTITY);
                createInfo.components().b(VK_COMPONENT_SWIZZLE_IDENTITY);
                createInfo.components().a(VK_COMPONENT_SWIZZLE_IDENTITY);

                createInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                createInfo.subresourceRange().baseMipLevel(0);
                createInfo.subresourceRange().levelCount(1);
                createInfo.subresourceRange().baseArrayLayer(0);
                createInfo.subresourceRange().layerCount(1);

                LongBuffer pImageView = stack.mallocLong(1);
                if(vkCreateImageView(device, createInfo, null, pImageView) != VK_SUCCESS)
                    throw new RuntimeException("Failed to create image view");

                swapchainImageViews.add(pImageView.get(0));
            }   
            System.out.println("Image Views successfully created");

        }
    }

    private void createRenderPass(){
        try(MemoryStack stack = stackPush()){
            //Describe the color image that render to
            VkAttachmentDescription.Buffer colorAttachment = VkAttachmentDescription.calloc(1, stack);
            colorAttachment.format(swapchainImageFormat);
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT);//no anti-aliasing 

            //to do before and after rendering
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            //create a referce to attach the subpass
            VkAttachmentReference.Buffer colorAttachmentRef = VkAttachmentReference.calloc(1, stack);
            colorAttachmentRef.attachment(0);
            colorAttachmentRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            //define main graphic subpass
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            subpass.colorAttachmentCount(1);
            subpass.pColorAttachments(colorAttachmentRef);

            //create subpass
            VkSubpassDependency.Buffer dependancy = VkSubpassDependency.calloc(1,stack);
            dependancy.srcSubpass(VK_SUBPASS_EXTERNAL);
            dependancy.dstSubpass(0);
            dependancy.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependancy.srcAccessMask(0);
            dependancy.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependancy.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            //Packtogeather and buld render pass
            VkRenderPassCreateInfo renderPathInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPathInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            renderPathInfo.pAttachments(colorAttachment);
            renderPathInfo.pSubpasses(subpass);
            renderPathInfo.pDependencies(dependancy);

            LongBuffer pRenderPass = stack.mallocLong(1);
            if(vkCreateRenderPass(device, renderPathInfo, null, pRenderPass)!= VK_SUCCESS)
                throw new RuntimeException("Failed to create render pass");

            renderPass = pRenderPass.get(0);
            System.out.println("Render Pass successfuly created");

        }
    }

    private long createShaderModule(byte[] code){
        try(MemoryStack stack = stackPush()){
            ByteBuffer codeBuffer = ByteBuffer.allocateDirect(code.length);
            codeBuffer.put(code);
            codeBuffer.flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc();
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(codeBuffer);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (vkCreateShaderModule(device, createInfo, null, pShaderModule) !=VK_SUCCESS)
                throw new RuntimeException("Failed to create shader module");
            return pShaderModule.get(0);
        }
    }

    private void createGraphicsPipeline(long renderPass) throws IOException {
        try (MemoryStack stack = stackPush()) {
            long vertShaderModule = createShaderModule(Files.readAllBytes(Paths.get("src/main/resources/shaders/vert.spv")));
            long fragShaderModule = createShaderModule(Files.readAllBytes(Paths.get("src/main/resources/shaders/frag.spv")));

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            shaderStages.get(0).stage(VK_SHADER_STAGE_VERTEX_BIT);
            shaderStages.get(0).module(vertShaderModule);
            shaderStages.get(0).pName(stack.UTF8("main"));

            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            shaderStages.get(1).stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            shaderStages.get(1).module(fragShaderModule);
            shaderStages.get(1).pName(stack.UTF8("main"));

            // Describe the binding (spacing between vertices - 5 floats per vertex)
            VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack);
            bindingDescription.binding(0);
            bindingDescription.stride(5 * Float.BYTES); 
            bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            //Describe the attributes (where position and color are inside the stride)
            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(2, stack);

            // Position (Location 0)
            attributeDescriptions.get(0).binding(0);
            attributeDescriptions.get(0).location(0);
            attributeDescriptions.get(0).format(VK_FORMAT_R32G32_SFLOAT); // vec2
            attributeDescriptions.get(0).offset(0);

            // Color (Location 1)
            attributeDescriptions.get(1).binding(0);
            attributeDescriptions.get(1).location(1);
            attributeDescriptions.get(1).format(VK_FORMAT_R32G32B32_SFLOAT); // vec3
            attributeDescriptions.get(1).offset(2 * Float.BYTES); // starts after the 2 position floats

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            vertexInputInfo.pVertexBindingDescriptions(bindingDescription);
            vertexInputInfo.pVertexAttributeDescriptions(attributeDescriptions);
            

            // input assembly
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            // ViewPort and Scissor (UPDATED to use dynamic swapchain extent)
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f);
            viewport.y(0.0f);
            viewport.width((float) swapchainExtent.width());
            viewport.height((float) swapchainExtent.height());
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(swapchainExtent.width(), swapchainExtent.height());

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            viewportState.pViewports(viewport);
            viewportState.pScissors(scissor);

            // Rasterizer
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);
            rasterizer.cullMode(VK_CULL_MODE_NONE);
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.lineWidth(1.0f);

            // Color Blending
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            colorBlendAttachment.blendEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.pAttachments(colorBlendAttachment);

            // pipeline layout
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

            pipelineLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
            
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            pipelineLayout = pPipelineLayout.get(0);

            // Create the Pipeline
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT));
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.renderPass(renderPass);
            pipelineInfo.subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create graphics pipeline!");
            }
            graphicsPipeline = pPipeline.get(0);

            // Cleanup modules
            vkDestroyShaderModule(device, vertShaderModule, null);
            vkDestroyShaderModule(device, fragShaderModule, null);
            
            System.out.println("Graphics Pipeline successfully created!");
        }
    }

    private void createFramebuffers() {
        swapchainFramebuffers = new ArrayList<>(swapchainImageViews.size());

        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < swapchainImageViews.size(); i++) {
                long imageView = swapchainImageViews.get(i);
                
                LongBuffer pFramebuffer = stack.mallocLong(1);
                
                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                framebufferInfo.renderPass(renderPass);
                framebufferInfo.pAttachments(stack.longs(imageView));
                
                // UPDATED: Use dynamic swapchain extent instead of hardcoded 800x600
                framebufferInfo.width(swapchainExtent.width());
                framebufferInfo.height(swapchainExtent.height());
                framebufferInfo.layers(1);

                if (vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer!");
                }
                swapchainFramebuffers.add(pFramebuffer.get(0));
            }
            
            System.out.println("Framebuffers successfully created!");
        }
    }

    private void createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT); // Allows re-record
            poolInfo.queueFamilyIndex(graphicsQueueFamilyIndex);

            LongBuffer pCommandPool = stack.mallocLong(1);
            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool!");
            }
            commandPool = pCommandPool.get(0);
        }
    }

    private void createVertexBuffer() {
        try (MemoryStack stack = stackPush()) {
            long bufferSize = VERTICES.length * Float.BYTES;

            //Create Staging Buffer (Host Visible)
            LongBuffer pStagingBuffer = stack.mallocLong(1);
            LongBuffer pStagingBufferMemory = stack.mallocLong(1);

            createBuffer(bufferSize, 
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 
                pStagingBuffer, pStagingBufferMemory);

            long stagingBuffer = pStagingBuffer.get(0);
            long stagingBufferMemory = pStagingBufferMemory.get(0);

            //Map Memory and Copy Data to Staging Buffer
            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(device, stagingBufferMemory, 0, bufferSize, 0, data);
            org.lwjgl.system.MemoryUtil.memFloatBuffer(data.get(0), VERTICES.length).put(VERTICES);
            vkUnmapMemory(device, stagingBufferMemory);

            //Create Actual Vertex Buffer (Device Local)
            LongBuffer pVertexBuffer = stack.mallocLong(1);
            LongBuffer pVertexBufferMemory = stack.mallocLong(1);

            createBuffer(bufferSize, 
                VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 
                pVertexBuffer, pVertexBufferMemory);

            vertexBuffer = pVertexBuffer.get(0);
            vertexBufferMemory = pVertexBufferMemory.get(0);

            //Copy data from Staging Buffer to Device Local Buffer
            copyBuffer(stagingBuffer, vertexBuffer, bufferSize);

            //Clean up Staging Buffer
            vkDestroyBuffer(device, stagingBuffer, null);
            vkFreeMemory(device, stagingBufferMemory, null);
        }
    }

    private void createCommandBuffers() {
        commandBuffers = new ArrayList<>(swapchainFramebuffers.size());
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(swapchainFramebuffers.size());

            PointerBuffer pCommandBuffers = stack.mallocPointer(swapchainFramebuffers.size());
            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers!");
            }

            for (int i = 0; i < pCommandBuffers.capacity(); i++) {
                commandBuffers.add(pCommandBuffers.get(i));
            }
        }
    }
    private void recordCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < commandBuffers.size(); i++) {
                VkCommandBuffer commandBuffer = new VkCommandBuffer(commandBuffers.get(i), device);
                
                // Begin recording
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
                beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                
                if (vkBeginCommandBuffer(commandBuffer, beginInfo) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to begin recording command buffer!");
                }

                // Start Render Pass
                VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
                renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
                renderPassInfo.renderPass(renderPass);
                renderPassInfo.framebuffer(swapchainFramebuffers.get(i));
                renderPassInfo.renderArea().offset().set(0, 0);
                
                // Use dynamic swapchain extent
                renderPassInfo.renderArea().extent().set(swapchainExtent.width(), swapchainExtent.height());
                
                // reset screen
                VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
                clearValues.color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));
                renderPassInfo.clearValueCount(1);
                renderPassInfo.pClearValues(clearValues);

                vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

                // Bind Pipeline
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
                
                // Bind Vertex Buffer
                LongBuffer vertexBuffers = stack.longs(vertexBuffer);
                LongBuffer offsets = stack.longs(0);
                vkCmdBindVertexBuffers(commandBuffer, 0, vertexBuffers, offsets);

                //Bind Index Buffer (specify 16-bit unsigned integer type because we used short[])
                vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT16);

                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(descriptorSets.get(i)), null);

                //Draw Indexed instead of Draw
                vkCmdDrawIndexed(commandBuffer, INDICES.length, 1, 0, 0, 0);

                // End Recording
                vkCmdEndRenderPass(commandBuffer);
                if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to record command buffer!");
                }
            }
        }
    }

    private void createSyncObjects() {
        imageAvailableSemaphores = new ArrayList<>(MAX_FRAMES_IN_FLIGHT);
        renderFinishedSemaphores = new ArrayList<>(MAX_FRAMES_IN_FLIGHT);
        inFlightFences = new ArrayList<>(MAX_FRAMES_IN_FLIGHT);

        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT); // Start signaled

            LongBuffer pImageAvailable = stack.mallocLong(1);
            LongBuffer pRenderFinished = stack.mallocLong(1);
            LongBuffer pInFlightFence = stack.mallocLong(1);

            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                if (vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailable) != VK_SUCCESS ||
                    vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinished) != VK_SUCCESS ||
                    vkCreateFence(device, fenceInfo, null, pInFlightFence) != VK_SUCCESS) {
                    
                    throw new RuntimeException("Failed to create synchronization objects for a frame!");
                }
                
                imageAvailableSemaphores.add(pImageAvailable.get(0));
                renderFinishedSemaphores.add(pRenderFinished.get(0));
                inFlightFences.add(pInFlightFence.get(0));
            }
        }
    }

    private void cleanupSwapchain() {
        for (long framebuffer : swapchainFramebuffers) {
            vkDestroyFramebuffer(device, framebuffer, null);
        }
        
        // Safely free command buffers using a MemoryStack
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(commandBuffers.size());
            for (int i = 0; i < commandBuffers.size(); i++) {
                pCommandBuffers.put(i, commandBuffers.get(i));
            }
            vkFreeCommandBuffers(device, commandPool, pCommandBuffers);
        }

        vkDestroyPipeline(device, graphicsPipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyRenderPass(device, renderPass, null);

        for (long imageView : swapchainImageViews) {
            vkDestroyImageView(device, imageView, null);
        }

        vkDestroySwapchainKHR(device, swapchain, null);

        for (int i = 0; i < swapchainImages.size(); i++) {
            vkDestroyBuffer(device, uniformBuffers.get(i), null);
            vkFreeMemory(device, uniformBuffersMemory.get(i), null);
        }
        
        vkDestroyDescriptorPool(device, descriptorPool, null);  
    }

    public void recreateSwapchain(Window window) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(windowHandle, width, height);
            
            // Pause execution if the window is minimized
            while (width.get(0) == 0 || height.get(0) == 0) {
                glfwGetFramebufferSize(windowHandle, width, height);
                glfwWaitEvents();
            }
        }

        vkDeviceWaitIdle(device);

        cleanupSwapchain();

        createSwapchain();
        createImageViews();
        createRenderPass();
        try {
            createGraphicsPipeline(renderPass);
        } catch (IOException e) {
            throw new RuntimeException("Failed to recreate graphics pipeline", e);
        }
        createFramebuffers();
        
        createUniformBuffers();
        createDescriptorPool();
        createDescriptorSets();

        createCommandBuffers();
        recordCommandBuffers();
    }

    private int findMemoryType(int typeFilter, int properties) {
        VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                memProperties.free();
                return i;
            }
        }
        memProperties.free();
        throw new RuntimeException("Failed to find suitable memory type!");
    }

    private void createBuffer(long size, int usage, int properties, LongBuffer pBuffer, LongBuffer pBufferMemory) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            if (vkCreateBuffer(device, bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create buffer!");
            }

            VkMemoryRequirements memRequirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, pBuffer.get(0), memRequirements);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memRequirements.size());
            allocInfo.memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), properties));

            if (vkAllocateMemory(device, allocInfo, null, pBufferMemory) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate buffer memory!");
            }

            vkBindBufferMemory(device, pBuffer.get(0), pBufferMemory.get(0), 0);
        }
    }

    private VkCommandBuffer beginSingleTimeCommands() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandPool(commandPool);
            allocInfo.commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);

            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer, beginInfo);

            return commandBuffer;
        }
    }

    private void endSingleTimeCommands(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(commandBuffer);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer));

            vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE);
            
            // Wait for the transfer to complete
            vkQueueWaitIdle(graphicsQueue);

            vkFreeCommandBuffers(device, commandPool, commandBuffer);
        }
    }

    private void copyBuffer(long srcBuffer, long dstBuffer, long size) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = beginSingleTimeCommands();

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(0);
            copyRegion.size(size);

            vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion);

            endSingleTimeCommands(commandBuffer);
        }
    }

    private void createIndexBuffer() {
        try (MemoryStack stack = stackPush()) {
            long bufferSize = INDICES.length * Short.BYTES;

            //Create Staging Buffer
            LongBuffer pStagingBuffer = stack.mallocLong(1);
            LongBuffer pStagingBufferMemory = stack.mallocLong(1);

            createBuffer(bufferSize, 
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 
                pStagingBuffer, pStagingBufferMemory);

            long stagingBuffer = pStagingBuffer.get(0);
            long stagingBufferMemory = pStagingBufferMemory.get(0);

            //Map and Copy Data
            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(device, stagingBufferMemory, 0, bufferSize, 0, data);
            
            // Use memShortBuffer for the short[] array
            org.lwjgl.system.MemoryUtil.memShortBuffer(data.get(0), INDICES.length).put(INDICES);
            
            vkUnmapMemory(device, stagingBufferMemory);

            //Create Actual Index Buffer
            LongBuffer pIndexBuffer = stack.mallocLong(1);
            LongBuffer pIndexBufferMemory = stack.mallocLong(1);

            createBuffer(bufferSize, 
                VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, 
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 
                pIndexBuffer, pIndexBufferMemory);

            indexBuffer = pIndexBuffer.get(0);
            indexBufferMemory = pIndexBufferMemory.get(0);

            //Copy and Cleanup
            copyBuffer(stagingBuffer, indexBuffer, bufferSize);

            vkDestroyBuffer(device, stagingBuffer, null);
            vkFreeMemory(device, stagingBufferMemory, null);
        }
    }

    private void createDescriptorSetLayout() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer uboLayoutBinding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            uboLayoutBinding.binding(0);
            uboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            uboLayoutBinding.descriptorCount(1);
            uboLayoutBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(uboLayoutBinding);

            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout!");
            }
            descriptorSetLayout = pDescriptorSetLayout.get(0);
        }
    }

    private void createUniformBuffers() {
        int bufferSize = 3 * 16 * Float.BYTES; // 3 Matrix4f objects (16 floats each)
        int count = swapchainImages.size();

        uniformBuffers = new ArrayList<>(count);
        uniformBuffersMemory = new ArrayList<>(count);
        uniformBuffersMapped = new ArrayList<>(count);

        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            LongBuffer pBufferMemory = stack.mallocLong(1);

            for (int i = 0; i < count; i++) {
                createBuffer(bufferSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, 
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 
                    pBuffer, pBufferMemory);

                uniformBuffers.add(pBuffer.get(0));
                uniformBuffersMemory.add(pBufferMemory.get(0));

                PointerBuffer data = stack.mallocPointer(1);
                vkMapMemory(device, pBufferMemory.get(0), 0, bufferSize, 0, data);
                uniformBuffersMapped.add(data.get(0));
            }
        }
    }

    private void createDescriptorPool() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            poolSize.descriptorCount(swapchainImages.size());

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSize);
            poolInfo.maxSets(swapchainImages.size());

            LongBuffer pDescriptorPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor pool!");
            }
            descriptorPool = pDescriptorPool.get(0);
        }
    }

    private void createDescriptorSets() {
        try (MemoryStack stack = stackPush()) {
            int count = swapchainImages.size();
            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) {
                layouts.put(i, descriptorSetLayout);
            }

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(layouts);

            LongBuffer pDescriptorSets = stack.mallocLong(count);
            if (vkAllocateDescriptorSets(device, allocInfo, pDescriptorSets) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate descriptor sets!");
            }

            descriptorSets = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long descriptorSet = pDescriptorSets.get(i);
                descriptorSets.add(descriptorSet);

                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.buffer(uniformBuffers.get(i));
                bufferInfo.offset(0);
                bufferInfo.range(3 * 16 * Float.BYTES);

                VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
                descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                descriptorWrite.dstSet(descriptorSet);
                descriptorWrite.dstBinding(0);
                descriptorWrite.dstArrayElement(0);
                descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

                descriptorWrite.descriptorCount(1);
                
                descriptorWrite.pBufferInfo(bufferInfo);

                vkUpdateDescriptorSets(device, descriptorWrite, null);
            }
        }
    }

    private void updateUniformBuffer(int currentImage) {
        float time = (System.nanoTime() - startTime) / 1E9f;

        //Model: Rotate 90 degrees per second on the Z axis
        Matrix4f model = new Matrix4f().rotate((float) (time * Math.toRadians(90.0)), 0.0f, 0.0f, 1.0f);
        
        //View: Look at the center from an angle above
        Matrix4f view = new Matrix4f().lookAt(2.0f, 2.0f, 2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f);
        
        //Projection: 45 degree field of view, matching the current window aspect ratio
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(45.0), 
        (float) swapchainExtent.width() / (float) swapchainExtent.height(), 0.1f, 10.0f, true);
        
        // Invert the Y axis because Vulkan's Y coordinate points down, but JOML expects OpenGL's pointing up
        proj.m11(proj.m11() * -1); 

        // Directly copy the JOML matrices into the mapped GPU pointer
        java.nio.FloatBuffer data = org.lwjgl.system.MemoryUtil.memFloatBuffer(uniformBuffersMapped.get(currentImage), 3 * 16);
        model.get(0, data);
        view.get(16, data);
        proj.get(32, data);
    }

    public void cleanup() {
        vkDeviceWaitIdle(device);

        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);

        //Wait for GPU to finish everything before destroying anything
        

        //Clean up our static buffers
        vkDestroyBuffer(device, indexBuffer, null);
        vkFreeMemory(device, indexBufferMemory, null);
        
        vkDestroyBuffer(device, vertexBuffer, null);
        vkFreeMemory(device, vertexBufferMemory, null);

        //Clean up swapchain-dependent objects (pipeline, framebuffers, etc.)
        cleanupSwapchain();

        //Destroy sync objects
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
            vkDestroyFence(device, inFlightFences.get(i), null);
        }

        //Destroy core Vulkan objects
        if (commandPool != 0) {
            vkDestroyCommandPool(device, commandPool, null);
        }
        if (device != null) {
            vkDestroyDevice(device, null);
        }
        if (surface != 0) {
            vkDestroySurfaceKHR(instance, surface, null);
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
        }
    }
}