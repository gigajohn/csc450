package com.jlathrop.engine.graphics.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;


import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
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

    private static final boolean ENABLE_VALIDATION_LAYERS = true;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private List<Long> swapchainFramebuffers;

    public void init(long windowHandle) throws IOException{
        createInstance();
        createSurface(windowHandle);
        pickPhysicalDevice();
        createLogicalDevice();
        createSwapchain();
        createImageViews();
        createRenderPass();
        createGraphicsPipeline(renderPass);
        createFramebuffers();
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

            IntBuffer presentSupport = stack.mallocInt(1);

            for (int i = 0; i < queueFamilies.capacity(); i++) {
                if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    graphicsQueueFamilyIndex = i;
                }

                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport);
                if (presentSupport.get(0) == VK_TRUE) {
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

    private void createSwapchain(){
        try(MemoryStack stack = stackPush()){
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);

            VkExtent2D extent = VkExtent2D.calloc(stack);
            extent.width(800);
            extent.height(600);

            int imageCount = capabilities.minImageCount() +1;
            if(capabilities.maxImageCount()>0 && imageCount > capabilities.maxImageCount())
                imageCount = capabilities.maxImageCount();

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(surface);
            createInfo.minImageCount(imageCount);

            createInfo.imageFormat(VK_FORMAT_B8G8R8A8_SRGB);
            createInfo.imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

            if(graphicsQueueFamilyIndex != presentQueueFamilyIndex){
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(graphicsQueueFamilyIndex, presentQueueFamilyIndex));

            }else
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);

            createInfo.preTransform(capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(VK_PRESENT_MODE_FIFO_KHR);//VSYNC
            createInfo.clipped();
            createInfo.oldSwapchain(VK_NULL_HANDLE);

            LongBuffer pSwapchain = stack.mallocLong(1);
            if(vkCreateSwapchainKHR(device, createInfo, null, pSwapchain) != VK_SUCCESS)
                throw new RuntimeException("Failed to create swapchain");

            swapchain = pSwapchain.get(0);
            System.out.println("Swapchain succesfully created");

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
                createInfo.format(VK_FORMAT_B8G8R8A8_SRGB);

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
            colorAttachment.format(VK_FORMAT_B8G8R8A8_SRGB);
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

    private void createGraphicsPipeline(long renderPass) throws IOException{
        try(MemoryStack stack = stackPush()){
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

            //vertex input
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            //input assembly
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            //ViewPort and Scissor
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f); viewport.y(0.0f);
            viewport.width(800.0f); viewport.height(600.0f);
            viewport.minDepth(0.0f); viewport.maxDepth(1.0f);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(800, 600);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            viewportState.pViewports(viewport);
            viewportState.pScissors(scissor);

            //Rasterizer
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);
            rasterizer.cullMode(VK_CULL_MODE_BACK_BIT);
            rasterizer.frontFace(VK_FRONT_FACE_CLOCKWISE);
            rasterizer.lineWidth(1.0f);

            //Color Blending
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            colorBlendAttachment.blendEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.pAttachments(colorBlendAttachment);

            //pipeline layout
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            pipelineLayout = pPipelineLayout.get(0);

            //Create the Pipeline
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

    private void createFramebuffers(){
        swapchainFramebuffers = new ArrayList<>(swapchainImageViews.size());

        try(MemoryStack stack = stackPush()){
            for (long imageView : swapchainFramebuffers) {
            LongBuffer pFramebuffer = stack.mallocLong(1);
            
            LongBuffer attachments = stack.longs(imageView);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
            framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            framebufferInfo.renderPass(renderPass);
            framebufferInfo.pAttachments(attachments);
            framebufferInfo.width(800);
            framebufferInfo.height(600);
            framebufferInfo.layers(1);

            if (vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create framebuffer!");
            }
            swapchainFramebuffers.add(pFramebuffer.get(0));
        }
        System.out.println("Framebuffers successfully created!");
        }
    }

    public void cleanup() {
        if (swapchainFramebuffers != null) {
            for (Long framebuffer : swapchainFramebuffers) {
                vkDestroyFramebuffer(device, framebuffer, null);
            }
        }
        if (graphicsPipeline != 0){
             vkDestroyPipeline(device, graphicsPipeline, null);
        }
        if (pipelineLayout != 0) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
        }
        if (renderPass != 0) {
            vkDestroyRenderPass(device, renderPass, null);
        }
        if (swapchainImageViews != null) {
            for (Long imageView : swapchainImageViews) {
                vkDestroyImageView(device, imageView, null);
            }
        }
        if(swapchain != 0){
            vkDestroySwapchainKHR(device, swapchain, null);
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