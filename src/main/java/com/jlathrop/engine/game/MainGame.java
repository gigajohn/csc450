package com.jlathrop.engine.game;
import com.jlathrop.engine.core.Window;
import com.jlathrop.engine.graphics.vulkan.VulkanContext;

public class MainGame {
    public void run(){
        Window window = new Window("Vulkan Engine", 800, 600);
        window.init();

        VulkanContext vulkan = new VulkanContext();
        vulkan.init(window.getHandle());

        //engine Loop
        while(!window.shouldClose()){
            window.update();
        }

        //reverse of setup
        vulkan.cleanup();
        window.cleanup();
    }

    public static void main(String[] args) {
        new MainGame().run();
    }
}
