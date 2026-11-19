package com.eventmanager.app.setup

/** Real Firebase CLI output captured from users, verbatim. */
object SetupErrorFixtures {
    val WORKDIR = """/Users/leonardomondada/.noctulist/tools/npm-global/lib/node_modules/firebase-tools/lib/logger.js:148
    throw new Error(`Unable to obtain permissions for ${'$'}{baseName}${'$'}{ext}`);
    ^

Error: Unable to obtain permissions for firebase-debug.log
    at findAvailableLogFile (/Users/leonardomondada/.noctulist/tools/npm-global/lib/node_modules/firebase-tools/lib/logger.js:148:11)
    at useFileLogger (/Users/leonardomondada/.noctulist/tools/npm-global/lib/node_modules/firebase-tools/lib/logger.js:169:25)
    at cli (/Users/leonardomondada/.noctulist/tools/npm-global/lib/node_modules/firebase-tools/lib/bin/cli.js:53:52)
    at Object.<anonymous> (/Users/leonardomondada/.noctulist/tools/npm-global/lib/node_modules/firebase-tools/lib/bin/firebase.js:27:5)

Node.js v22.23.5"""

    val BUILD_SA = """i  functions: creating Node.js 22 (2nd Gen) function noctulist:onMailOutboxWritten(europe-west6)...
i  functions: creating Node.js 22 (2nd Gen) function noctulist:onSignupWritten(europe-west6)...
Build failed with status: FAILURE. Could not build the function due to a missing permission on the build service account. If you didn't revoke that permission explicitly, this could be caused by a change in the organization policies. Please refer to the following documentation for more details and resolution: https://cloud.google.com/functions/docs/troubleshooting#build-service-account
 You can also view the logs at https://console.cloud.google.com/cloud-build/builds;region=europe-west6/b37be682-4d2a-4fe5-89f5-dfa2b5c72b17?project=926525401482.

⚠  functions: Deploys failed. Skipping deletes.

Functions deploy had errors with the following functions:
 noctulist:onMailOutboxWritten(europe-west6)
 noctulist:onSignupWritten(europe-west6)
i  functions: Configured cleanup policy for repository in europe-west6.
Error: There was an error deploying functions:
- Error Failed to create function onMailOutboxWritten in region europe-west6
- Error Failed to create function onSignupWritten in region europe-west6"""

    val HTTPS_TO_BACKGROUND = """=== Deploying to 'noctuliste-cn'...

i  deploying functions, hosting
i  functions: preparing codebase noctulist for deployment
i  functions: ensuring required API cloudfunctions.googleapis.com is enabled...
⚠  functions: package.json indicates an outdated version of firebase-functions. Please upgrade using npm install --save firebase-functions@latest in your functions directory.
i  functions: Loaded environment variables from functions/.env.noctuliste-cn
Serving at port 8145

i  functions: ensuring required API secretmanager.googleapis.com is enabled...
✔  functions: functions source uploaded successfully
i  hosting[noctuliste-cn]: beginning deploy...
✔  hosting[noctuliste-cn]: file upload complete

Error: [noctulist:onMailOutboxWritten(europe-west6)] Changing from an HTTPS function to a background triggered function is not allowed. Please delete your function and create a new one instead."""

    val LIST_FUNCTIONS = """=== Deploying to 'noctuliste-cn'...

i  deploying functions, hosting
i  functions: preparing codebase noctulist for deployment
i  functions: ensuring required API cloudfunctions.googleapis.com is enabled...
i  functions: Loading and analyzing source code for codebase noctulist to determine what to deploy
Serving at port 8949


Error: Failed to list functions for noctuliste-cn"""
}
