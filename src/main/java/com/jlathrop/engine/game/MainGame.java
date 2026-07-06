package com.jlathrop.engine.game;
import com.jlathrop.engine.core.Window;

public class MainGame {
    public void run(){
        Window window = new Window("Vulkan Engine", 800, 600);
        window.init();

        //engine Loop
        while(!window.shouldClose()){
            window.update();
        }

        window.cleanup();
    }

    public static void main(String[] args) {
        new MainGame().run();
    }
}
