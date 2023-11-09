# Jactl-Vertx

The Jactl-Vertx project is a project that adds a [Vert.x](https://vertx.io/) based execution environment
to the [Jactl](https://jactl.io) scripting language along with some additional methods
and an example function that shows how to extend Jactl with your own application specific functions/methods.

## Dependency

To use this library you will need to add a dependency on the `jactl-vertx` library.

### Gradle

In the `dependencies` section of your `build.gradle` file:
```groovy
implementation group: 'io.jactl', name: 'jactl-vertx', version: '1.3.0'
```

If you want to use the example `sendReceiveJson()` function then also include a dependency on the `tests` jar (you
will need to have built the `tests` jar locally as this is not published in Maven Central):
```groovy
implementation group:'io.jactl', name:'jactl-vertx', version:'1.3.0', classifier:'tests'
```

### Maven

In the `dependencies` section of your `pom.xml`:
```xml
<dependency>
  <groupId>io.jactl</groupId>
  <artifactId>jactl-vertx</artifactId>
  <version>1.3.0</version>
</dependency>
```

If you have built the `tests` jar locally and want to use it in a test application then add this (the `tests` jar is
not published in Maven Central):
```xml
<dependency>
  <groupId>io.jactl</groupId>
  <artifactId>jactl-vertx</artifactId>
  <version>1.3.0</version>
  <classifier>tests</classifier>
</dependency>
```

# Building

## Requirements

* Java 11+
* Jactl 1.3.0
* Gradle 8.0.2
* Vert.x 4.4.1
* jackson-databind 2.0.1

## Build

Download a zip file of the source from GitHub or use `git` to clone the repository:

```shell
git clone https://github.com/jaccomoc/jactl-vertx.git
cd jactl-vertx
./gradlew build testJar
```

That will build `jactl-vertx-${VERSION}.jar` and `jactl-vertx-${VERSION}-tests.jar` under the `build/libs` directory
where `${VERSION}` is the current version or the version of the tag/branch you have checked out.

To push the `jactl-vertx` jar to your local Maven repository you can use `publishToMavenLocal`:
```shell
./gradlew build testJar publishToMavenLocal
```

# Integration

## Jactl Environment Class

The `io.jactl.vertx.JactlVertxEnv` class provides a bridge between the Jactl runtime and the Vert.x runtime
environment to allow Jactl to run on Vert.x event-loop threads and schedule blocking work on Vert.x blocking worker
threads.

It should be constructed by passing a `Vertx` instance and the `JactlVertxEnv` should be set on the `JactlContext`
object that you create using the `JactlContext.environment()` method.
See next section for an example.

## Application Integration

To add the additional functions/methods from the `jactl-vertx` library to your Jactl based application you will need
to make sure that the functions/methods are registered with the Jactl runtime by invoking
`io.jactl.vertx.JsonFunctions.registerFunctions()` (for the JSON methods) and, if you want to use the example
`sendReceiveJson()` function, invoking `example.io.jactl.vertx.VertxFunctions.registerFunctions()`.

Both these methods should be passed the instance of the `io.jactl.vertx.JactlVertxEnv` class that is also passed to
the `JactlContext.environment()` method when constructing your `JactlContext` object.

For example, here we construct the `JactlVertxEnv` object and pass it to the `registerFunctions()` calls as well
as setting it on our `JactlContext` object:
```java
class MyVertxApp {
  Vertx vertx;
  public void init() {
    this.vertx         = Vertx.vertx();
    JactlVertxEnv env = new JactlVertxEnv(vertx);
    
    // Register functions/methods
    JsonFunctions.registerFunctions(env);
    VertxFunctions.registerFunctions(env);   // If using example sendReceiveJson() method
    
    JactlContext context = JactlContext.create()
                                         .environment(env)
                                         .build();
    ...
  }
}
```

## Jactl REPL and Jactl Commandline Integration

To include these methods/functions in your Jactl REPL or Jactl commandline scripts you can set your `~/.jactlrc`
configuration file to include something like the following:

```groovy
def VERS = '1.3.0'                                                  // The jactl-vertx version to use
def LIBS = "~/.m2/repository/io/jactl/jactl-vertx/${VERS}"         // Location of the jars

// Specify the Vertx based environment class to use
environmentClass = 'io.jactl.vertx.JactlVertxEnv'

// List the extra jactl-vertx jars
extraJars        = [ "$LIBS/jactl-vertx-${VERS}.jar",
                     "$LIBS/jactl-vertx-${VERS}-tests.jar" ]

// List the function registration classes
functionClasses  = [ 'io.jactl.vertx.JsonFunctions',
                     'example.io.jactl.vertx.VertxFunctions' ]
```

> **Note**<br/>
> The `jactl-vertx` test jar is built as a "fat" jar and includes the dependencies it needs (including the
> Vert.x libraries) so we don't need to separately list the Vert.x jars as well.

## Functions

There are currently two Jactl methods for converting to/from JSON provided by the `jactl-vertx` library.
In addition, an example of a Jactl global function called `sendReceiveJson()` is provided in the `tests` jar.

### Object.toJson()

To convert a Jactl object to a JSON string use the method called `toJson()`:
```groovy
> [name:'Fred Smith', address:['123 High St', 'Faraway', 'Australia']].toJson()
{"name":"Fred Smith","address":["123 High St","Faraway","Australia"]}
> class User { def name; def userId }
> new User('Jane Doe', 'janed').toJson()
{"name":"Jane Doe","userId":"janed"}
```

### String.fromJson()

The `fromJson()` method is a new method for the String class which, given a JSON string, will convert from that
string back in to a Jactl object:
```groovy
> '{"name":"Jane Doe","userId":"janed"}'.fromJson()
[name:'Jane Doe', userId:'janed']
```

If the result is a Map. you can, of course, convert the Map into the actual desired type by coercing the Map
the right type.
For example:
```groovy
> class User { def name; def userId }
> def json = new User('Jane Doe', 'janed').toJson()
{"name":"Jane Doe","userId":"janed"}
> def userMap = json.fromJson()
[name:'Jane Doe', userId:'janed']
> userMap instanceof Map
true
> def user = json.fromJson() as User
[name:'Jane Doe', userId:'janed']
> user instanceof User
true
```

### sendReceiveJson(String url, def request)

The `sendReceiveJson()` function is an example function provided to show how to extend Jactl with your own
global functions and methods.
It will send a JSON encoded request to a remote URL and wait for the response.
It does a `POST` and passes the JSON as the request body to the remote server and then decodes the JSON response
that comes back.

The return value of the function is a Map that contains a field called `statusCode` with the HTTP status code
and then either:
* a field called `response` with the decoded JSON body if the status code is 2xx, or
* a field called `errorMsg` with the error message if a non-2xx status is received.

For example:
```groovy
> sendReceiveJson('http://localhost:58476/wordCount', [text:'here are some words to count'])
[response:6, statusCode:200]
```

And an example with named args:
```groovy
> sendReceiveJson(url:'http://localhost:58476/wordCount', request:[text:'here are some more words to be counted'])
[response:8, statusCode:200]
```

Here are some examples where an error occurrs:
```groovy
> sendReceiveJson('http://localhost:58476/bad', [arg:'abc'])
[errorMsg:'java.nio.file.NoSuchFileException: scripts/bad.jactl', statusCode:404]
> sendReceiveJson(url:'http://localhost:58476/wordCount', request:[bad:'here are some more words to be counted'])
[errorMsg:'jactl.runtime.DieError: Missing value for text field', statusCode:400]
```

# Example Application

In the `tests` jar is an example application showing how to ingrate a simple Vert.x based web server with Jactl
scripting.

The server listens for JSON requests and looks for the URI portion of the URL to work out what script to
run to handle the request.
For example, if the incoming URL is 'http://localhost:8080/doSomething' then it will look for a Jactl script
under the directory from where it is running called `scripts/doSomething.jactl`.
If the script exists it will compile it and invoke it with a global variable called `request` containing the
body of the request (already decoded).

The application will cache the compiled script so that it doesn't need to recompile it each time, but it
monitors the source code for any changes (every 5 seconds), and if it has changed it will recompile it
if another request for that script comes in.

To run the example application create a subdirectory called `scripts` under the location where you are
going to run from and then add the `jactl-vertx` jar and `jactl-vertx` `tests` jar to the java classpath
and invoke `io.jactl.vertx.example.ExampleWebServer`.
By default, it will listen on a random port:
```shell
$ java -cp jactl-vertx-1.3.0-tests.jar:jactl-vertx-1.3.0.jar io.jactl.vertx.example.ExampleWebServer
Listening on localhost:52178
```

If you pass in a port number on the command line it will use that instead:
```shell
$ java -cp jactl-vertx-1.3.0-tests.jar:jactl-vertx-1.3.0.jar io.jactl.vertx.example.ExampleWebServer 8080
Listening on localhost:8080
```

You can specify the host address to listen on by using `hostname:port`:
```shell
$ java -cp jactl-vertx-1.3.0-tests.jar:jactl-vertx-1.3.0.jar io.jactl.vertx.example.ExampleWebServer 8080
Listening on localhost:8080
```

If the `hostname` portion is blank (i.e. you pass it `:port`) then it will bind to all local addresses
and listen on the port.

If the `port` is blank (i.e. has the form `hostname:`) then it will bind to that address with a random
port.

Assume we run it on port 8080:
```shell
$ java -cp jactl-vertx-1.3.0-tests.jar:jactl-vertx-1.3.0.jar io.jactl.vertx.example.ExampleWebServer 8080
Listening on localhost:8080
```

Now assume we have created a Jactl script for counting words and put it in under `scripts/wordCount.jactl`:
```groovy
// Validate request. Should be of form: [text:'some text to be word counted']
die 'Missing value for text field' unless request instanceof Map && request.text
def badFields = request.filter{ k,v -> k != 'text' }.map{ k,v -> k }
die "Unknown field${badFields.size() > 1 ? 's' : ''}: ${badFields.join(', ')}" if badFields

request.text.split(/\s+/)         // split on whitespace
            .filter{ /^\w+$/r }   // filter for things that only contain word characters
            .size()               // return the count
```

We can then invoke the script from the Jactl REPL like this using the `sendReceiveJson()` example function
described previously:
```groovy
> sendReceiveJson(url:'http://localhost:8080/wordCount', request:[text:'here are some more words to be counted'])
[response:8, statusCode:200]
```
