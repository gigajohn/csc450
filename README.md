# csc450

– Are you in a Group?

No

– If so, who else is in your group?

N/A

– Do you have your GitHub account set up?

yes

– Do you have a public repository for your Project?

yes

– What is the link to your GitHub repository?

https://github.com/gigajohn/csc450/

– If you are in a group, does everyone have write access to the github repo?

N/A

– Do you have a “Hello World” program that compiles and runs?

yes

– Where is the entry point to your project? (src/main/Main.java for
example)

src/main/Main.java



----
Ideas
- Game engine
   - using vulkan as the graphical api
   - https://www.lwjgl.org/, for the game library 

my-game-engine/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/yourname/engine/
│   │   │   ├── core/                  # The main loop, window management, and input
│   │   │   │   ├── Window.java
│   │   │   │   └── EngineLoop.java
│   │   │   ├── ecs/                   # Entity-Component-System architecture
│   │   │   │   ├── Entity.java
│   │   │   │   └── System.java
│   │   │   ├── graphics/              # 100% abstract graphics interfaces
│   │   │   │   ├── Renderer.java      # Interface your game logic actually calls
│   │   │   │   ├── Mesh.java
│   │   │   │   └── vulkan/            # The ONLY place Vulkan code exists
│   │   │   │       ├── VulkanRenderer.java  # Implements Renderer interface
│   │   │   │       ├── VulkanContext.java   # Instance, Device, Swapchain
│   │   │   │       └── VulkanPipeline.java  # Shader loading and pipeline state
│   │   │   └── game/                  # The actual game built on top of the engine
│   │   │       └── MainGame.java
│   │   └── resources/                 # Non-code assets automatically bundled by Maven
│   │       ├── shaders/
│   │       │   ├── triangle.vert      # Vertex shader
│   │       │   └── triangle.frag      # Fragment shader
│   │       └── textures/
│   └── test/
│       └── java/com/yourname/engine/  # JUnit tests
│           └── ecs/
│               └── EcsLogicTest.java

