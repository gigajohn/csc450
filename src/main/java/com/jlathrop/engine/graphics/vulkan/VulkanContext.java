package com.jlathrop.engine.graphics.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;

import static org.lwjgl.vulkan.KHRSwapchain.*;

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

    private static final boolean ENABLE_VALIDATION_LAYERS = true;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    public void init(long windowHandle) {
        createInstance();
        createSurface(windowHandle);
        pickPhysicalDevice();
        createLogicalDevice();
        createSwapchain();
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

    public void cleanup() {
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