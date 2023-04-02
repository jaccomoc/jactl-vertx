# Jacsal-Vertx

The Jacsal-Vertx project is a project that adds a [Vert.x](https://vertx.io/) based execution environment
to the [Jacsal](https://github.com/jaccomoc/jacsal) scripting language along with some additional methods
and an example function that shows how to extend Jacsal with your own application specific functions/methods.

# Building

## Requirements

* Java 11+
* Jacsal 1.0
* Gradle 8.0.2
* Vert.x 4.4.0
* jackson-databind 2.0.1

## Build

```shell
git clone https://github.com/jaccomoc/jacsal-vertx.git
cd jacsal-vertx
./gradlew build testJar
```

That will build `jacsal-vertx-1.0.jar` and `jacsal-vertx-1.0-SNAPSHOT-tests.jar` under the `build/libs` directory.

To push to your Maven repository you can use `publishToMaven`:
```shell
./gradlew build testJar publishToMaven
```

## Integration

To include the library in your application add the following gradle dependency:
```groovy
implementation group:'jacsal', name:'jacsal-vertx', version:'1.0'
```

If you want to use the example `sendReceiveJson()` function then also include a dependency on the `tests` jar:
```groovy
implementation group:'jacsal', name:'jacsal-vertx', version:'1.0', classifier:'tests'
```

### Jacsal Environment Class

The `jacsal.vertx.JacsalVertxEnv` class provides a bridge between the Jacsal runtime and the Vert.x runtime
environment to allow Jacsal on Vert.x event-loop threads and schedule blocking work on Vert.x blocking worker
threads.

It should be constructed by passing a `Vertx` instance and the `JacsaVertxEnv` should be set on the `JacsalContext`
object that you create using the `JacsalContext.environment()` method.
See next section for an example.

### Application Integration

To add these addtional functions/methods to your Jacsal based application you will need to make sure that the
functions/methods are registered with the Jacsal runtime by invoking `jacsal.vertx.JsonFunctions.registerFunctions()`
(for the JSON methods) and, if you want to use the example `sendReceiveJson()` function, invoking
`jacsal.vertx.example.VertxFunctions.registerFunctions()`.

Both these methods should be passed the instance of the `jacsal.vertx.JacsalVertxEnv` class that is also passed to
the `JacsalContext.environment()` method when constructing your `JacsalContext` object.

For example, here we construct the `JacsalVertxEnv` object and pass it to the `registerFunctions()` calls as well
as setting it on our `JacsalContext` object:
```java
class MyVertxApp {
  Vertx vertx;
  public void init() {
    this.vertx         = Vertx.vertx();
    JacsalVertxEnv env = new JacsalVertxEnv(vertx);
    
    // Register functions/methods
    JsonFunctions.registerFunctions(env);
    VertxFunctions.registerFunctions(env);   // If using example sendReceiveJson() method
    
    JacsalContext context = JacsalContext.create()
                                         .environment(env)
                                         .build();
    ...
  }
}
```

### Jacsal REPL and Jacsal Commandline Integration

To include these methods/functions in your Jacsal REPL or Jacsal commandline scripts you can set your `~/.jacsalrc`
configuration file to include something like the following:

```groovy
def VERS = '1.0'                                                  // The jacsal-vertx version to use
def LIBS = "~/.m2/repository/jacsal/jacsal-vertx/${VERS}"         // Location of the jars

// Specify the Vertx based environment class to use
environmentClass = 'jacsal.vertx.JacsalVertxEnv'

// List the extra jacsal-vertx jars
extraJars        = [ "$LIBS/jacsal-vertx-${VERS}.jar",
                     "$LIBS/jacsal-vertx-${VERS}-tests.jar" ]

// List the function registration classes
functionClasses  = [ 'jacsal.vertx.JsonFunctions',
                     'jacsal.vertx.example.VertxFunctions' ]
```

> **Note**
> The `jacsal-vertx` test jar is built as an "uber" jar and includes the dependencies it needs (including the
> Vert.x libraries) so we don't need to separately list the Vert.x jars as well.

## Functions

There are currently two Jacsal methods for converting to/from JSON provided by the `jacsal-vertx` library.
In addition, an example of a Jacsal global function called `sendReceiveJson()` is provided in the `tests` jar.

### Object.toJson()

To convert a Jacsal object to a JSON string use the method called `toJson()`:
```groovy
> [name:'Fred Smith', address:['123 High St', 'Faraway', 'Australia']].toJson()
{"name":"Fred Smith","address":["123 High St","Faraway","Australia"]}
> class User { def name; def userId }
> new User('Jane Doe', 'janed').toJson()
{"name":"Jane Doe","userId":"janed"}
```

### String.fromJson()

The `fromJson()` method is a new method for the String class which, given a JSON string, will convert from that
string back in to a Jacsal object:
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

The `sendReceiveJson()` function is an example function provided to show how to extend Jacsal with your own
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
[errorMsg:'java.nio.file.NoSuchFileException: scripts/bad.jacsal', statusCode:404]
> sendReceiveJson(url:'http://localhost:58476/wordCount', request:[bad:'here are some more words to be counted'])
[errorMsg:'jacsal.runtime.DieError: Missing value for text field', statusCode:400]
```

