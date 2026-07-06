package com.jlathrop.engine.graphics.vulkan;

import java.nio.LongBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer; // Required for GPU counting

import javax.management.RuntimeErrorException;

import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanContext {
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;

    private VkDevice device;
    private VkQueue graphicQueue;
    private int graphicsQueueFamilyIndex = -1;

    private static final boolean ENABLE_VALIDATION_LAYERS = true;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    public void init(long windowHandle) {
        createInstance();
        createSurface(windowHandle);
        pickPhysicalDevice();
        createLogicalDevice();
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

            // We want the dedicated or integrated hardware GPU
            boolean isHardwareGpu = deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU || 
                                    deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;

            if (isHardwareGpu) {
                System.out.println("-> Selected GPU: " + deviceName);
                return true;
            }
            return false;
        }
    }

    private void findQuueueFamilies(VkPhysicalDevice device){
        try(MemoryStack stack = stackPush()){
            IntBuffer queueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            for( int i = 0; i < queueFamilies.capacity(); i++){
                if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    graphicsQueueFamilyIndex = i;
                    break;
                }
            }
            if(graphicsQueueFamilyIndex == -1) throw new RuntimeException("Failed to find a suitable graphics queue family");

        }
    }

    private void createLogicalDevice(){
        findQuueueFamilies(physicalDevice);

        try(MemoryStack stack = stackPush()){
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1,stack);
            queueCreateInfos.get(0).sType(VK_STRUCTURE_TYPE_EVENT_CREATE_INFO);
            queueCreateInfos.get(0).queueFamilyIndex(graphicsQueueFamilyIndex);

            //priority
            queueCreateInfos.get(0).pQueuePriorities(stack.floats(1.0f));

            //features needed
            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);

            //create logial dev
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures);
            
            PointerBuffer pDevice = stack.mallocPointer(1);
            if (vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create logical device");
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsQueueFamilyIndex, 0, pQueue);
            graphicQueue = new VkQueue(pQueue.get(0), device);

            System.out.println("Logical Device and Graphical Queue succesfully created");
        }

    }

    private void createSurface(long windowHandle){
        try(MemoryStack stack = stackPush()){
            LongBuffer pSurface = stack.mallocLong(1);
            if (glfwCreateWindowSurface(instance, windowHandle, null, pSurface) != VK_SUCCESS) throw new RuntimeException("Failed to create Window Surface");

            surface =pSurface.get(0);
            System.out.println("Window Surface successfully created");
        }
    }

    public void cleanup() {
        if(device != null)
            vkDestroyDevice(device, null);
        if (surface !=0)
            vkDestroySurfaceKHR(instance, surface, null);
        if (instance != null) 
            vkDestroyInstance(instance, null);
        
    }
}