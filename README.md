# InContext
<img src="logo.png" width="400" alt="InContext Logo"/>
<!-- Plugin description -->
**InContext** is a plugin that enhances code referencing in JetBrains IDEs. It allows you to create, share, and navigate precise file references in the format:

```
@project-name/path/to/file.ext:L10-15
```

### Key Features:

- **Copy References**: Select code and use <kbd>Cmd+Option+Control+C</kbd> (macOS) or right-click → Copy Reference to create a reference to the selected lines
- **Smart Navigation**: Click on any reference to navigate directly to the specified file and line range
- **Bi-directional Linking**: Navigate from references to code and from code back to references
- **AI Integration**: Share precise code references with AI coding assistants for better context

### Example Use Cases:

- Share specific code sections with teammates: `@auth-service/src/main/java/auth/SecurityConfig.java:L45-52`
- Reference code in documentation: `@frontend/components/Button.tsx:L24-35`
- Provide exact locations to AI tools: `@api/controllers/UserController.java:L78-92`

Working example: 

File open code : @main/kotlin/com/asanga/incontext/handlers/FileReferenceGotoDeclarationHandler.kt:L127-134
Copy action: @main/kotlin/com/asanga/incontext/actions/CopyReferenceAction.kt:L60-67

<video src="incontext.m4v" width="640" height="360" controls></video>

<!-- Plugin description end -->
