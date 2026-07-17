package com.jlathrop.engine.game;
import java.io.IOException;

import com.jlathrop.engine.core.Window;
import com.jlathrop.engine.graphics.vulkan.VulkanContext;

public class MainGame {
    private final Window window = new Window("Vulkan Engine", 800, 600);
    private final VulkanContext vulkan = new VulkanContext();

    public void run() throws IOException {
        window.init();
        vulkan.init(window.getHandle());

        try {
            while (!window.shouldClose()) {
                window.update();
                vulkan.drawFrame(window);
            }
        } finally {
            vulkan.cleanup();
            window.cleanup();
        }
    }

    public static void main(String[] args) throws IOException {
        new MainGame().run();
    }
}
