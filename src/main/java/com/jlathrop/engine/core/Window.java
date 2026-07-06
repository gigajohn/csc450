package com.jlathrop.engine.core;
import org.lwjgl.glfw.GLFWErrorCallback;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;


public class Window {
    private long windowHandle;
    private final int width;
    private final int height;
    private final String title;

    public Window(String title, int width, int height){
        this.title = title;
        this.width =width;
        this.height = height;
    }

    public void init(){
        //error handling
        GLFWErrorCallback.createPrint(System.err).set();

        if(!glfwInit()){
            throw new IllegalStateException("Unable to init GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); //Disable OpenGL context
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);   //Disable resizing initially

        windowHandle = glfwCreateWindow(width, height, title, NULL, NULL);//capital NULL required
        if(windowHandle == NULL)
            throw new RuntimeException("Failed to create GLFW window");

    }

    public void update(){
        glfwPollEvents();
    }

    public boolean shouldClose(){
        return glfwWindowShouldClose(windowHandle);
    }

    public void cleanup() {
        glfwDestroyWindow(windowHandle);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    //for vulkan
    public long getHandle() {
        return windowHandle;
    }

}
