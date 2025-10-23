# TCApi2Postman

## 🚀 What is this?

TCApi2Postman is application that converts auto-generated REST API documentation from Teamcenter into a fully-loaded Postman collection. 
It doesn’t just spit out a bunch of requests—it packs each method with detailed documentation, ready to roll in Postman. 

--

## 🎯 Features

- Transforms Teamcenter REST API documentation (structure.js) into a Postman collection.

- Auto-populates each request with rich, detailed documentation for every API method.

- Supports optional inclusion of internal APIs with the --include-internal flag.

- Allows customization via a config file with the --config option.

- Outputs a clean, ready-to-import .json file for Postman.

## 🧑‍💼 Who is it for?

Developers, QA engineers, Presales, etc. working with Teamcenter REST APIs

## 💊 Instructions

### Prerequisites

- **Java 17 or later** (e.g., Azul Zulu, OpenJDK, or Oracle JDK)
  - Verify installation: `java -version`
  - Set `JAVA_HOME` environment variable to your JDK installation directory

- **Maven 3.6 or later** (required to build from source)
  - Download from https://maven.apache.org/download.cgi or use a package manager (e.g., `choco install maven` on Windows with Chocolatey)
  - Verify installation: `mvn -version`

### Build TCApi2Postman from Source

If you're building from source, follow these steps:

1. Clone or download this repository
2. Navigate to the project directory
3. Run the build:
   ```bash
   mvn clean package
   ```
4. The compiled jar will be in the `target/` directory (named `TCApi2Postman-1.0.0.jar`)

### Get Your Input Data

You'll need the `structure.js` file from your Teamcenter setup (AWC layer):

- cd ...\aws2\stage
- initenv
- npm run genSoaApi
- cd ...\aws2\stage\out\soa\api

### Run TCApi2Postman

Use the command below to convert your API docs to a Postman collection:

**java -jar TCApi2Postman-1.0.0.jar** <structure.js> <out.postman_collection.json> [--include-internal] [--config <TCApi2Postman.config>]

**Example:**
```bash
java -jar TCApi2Postman-1.0.0.jar C:\Siemens\Teamcenter\13\aws2\stage\out\soa\api\structure.js D:\Temp\TcApi_collection.json --config TCApi2Postman.config
```


**Options:** 
**--include-internal:** Include internal APIs in the output (optional, for the brave).  
**--config <file>:** Point to a custom config file for extra control.

Import to Postman: Load the generated .json file into Postman, and you’re good to go!

<img width="1167" height="283" alt="image" src="https://github.com/user-attachments/assets/fbbcc01e-02d3-40bb-a0a8-e69b029b8ae4" />

<img width="673" height="36" alt="image" src="https://github.com/user-attachments/assets/2389e358-844e-4249-be6c-e90ff279eab3" />

<img width="1830" height="1001" alt="image" src="https://github.com/user-attachments/assets/9709650a-ee8e-4b00-90e3-3bd514342f43" />
