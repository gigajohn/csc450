error id: file:///C:/Users/lathr/Documents/GitHub/csc450/src/main/java/com/jlathrop/engine/core/Window.java:org/lwjgl/system/MemoryUtil#
file:///C:/Users/lathr/Documents/GitHub/csc450/src/main/java/com/jlathrop/engine/core/Window.java
empty definition using pc, found symbol in pc: org/lwjgl/system/MemoryUtil#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 165
uri: file:///C:/Users/lathr/Documents/GitHub/csc450/src/main/java/com/jlathrop/engine/core/Window.java
text:
```scala
package main.java.com.jlathrop.engine.core;
import org.lwjgl.glfw.GLFWErrorCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUt@@il.NULL;


public class Window {
    private long windowHadle;
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
        GLFWErrorCallBack.createPrint(System.err).set();

        if(!glwInit()){
            throw new IllegalStateException("Unable to init GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); //Disable OpenGL context
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);   //Disable resizing initially

        windowHadle = glfwCreateWindow(width, height, title, NULL, NULL);//capital NULL required
        if(windowHadle == NULL)
            throw new RuntimeException("Failed to create GLFW window");

    }

    public void update(){
        glfwPullEvents();
    }

    public boolean shouldClose(){
        return glfwWindowShouldClose(windowHadle);
    }

    public void cleanup() {
        glfwDestroyWindow(windowHandle);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    //for vulkan
    public long getHandle() {
        return windowHadle;
    }

}

```


#### Short summary: 

empty definition using pc, found symbol in pc: org/lwjgl/system/MemoryUtil#