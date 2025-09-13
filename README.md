# TCApi2Postman

TCApi2Postman
🚀 What is this?

TCApi2Postman is application that converts auto-generated REST API documentation from Teamcenter into a fully-loaded Postman collection. 
It doesn’t just spit out a bunch of requests—it packs each method with detailed documentation, ready to roll in Postman. 


🎯 Features

Transforms Teamcenter REST API documentation (structure.js) into a Postman collection.  
Auto-populates each request with rich, detailed documentation for every API method.  
Supports optional inclusion of internal APIs with the --include-internal flag.  
Allows customization via a config file with the --config option.  
Outputs a clean, ready-to-import .json file for Postman.

🧑‍💼 Who is it for?

Developers, QA engineers, Presales, etc. working with Teamcenter REST APIs

💊 Instructions

Prerequisites: Make sure you have Java (Azul Zulu: 21.0.8+9) installed
Grab your input: You’ll need the structure.js file from your Teamcenter setup (e.g., ...\aws2\stage\out\soa\api\structure.js):

- cd ...aws2\stage
- initenv
- npm run genSoaApi
- cd ...aws2\stage\out\soa\api
- 
Run TCApi2Postman: Use the command below to convert your API docs to a Postman collection.  
java TCApi2Postman <structure.js> <out.postman_collection.json> [--include-internal] [--config <TCApi2Postman.config>]

Example:java TCApi2Postman C:\Siemens\Teamcenter\13\aws2\stage\out\soa\api\structure.js D:\Temp\TcApi_collection.json --config TCApi2Postman.config


Options:  
--include-internal: Include internal APIs in the output (optional, for the brave).  
--config <file>: Point to a custom config file for extra control.

Import to Postman: Load the generated .json file into Postman, and you’re good to go!
