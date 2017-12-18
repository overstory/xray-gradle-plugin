# Gradle Plugin to Run XRay Tests

This project is a plugin for Gradle that will run XRay tests on MarkLogic (or any XQuery platform
where the tests can be invoked by an HTTP GET call to a URL).

### Using the XRay Plugin

To use this plugin with Gradle, all you need to do is reference it in your `build.gradle` file, in
a `plugins` block, like this:

```$groovy
plugins {
	id: 'uk.co.overstory.xray' version '1.0'
}
```

If you already have a `plugins` block, just add the above reference to it.  Check the
[Gradle.org plugins page](https://plugins.gradle.org/plugin/uk.co.overstory.xray) to find the latest version number of the plugin.

### What It Does

What this plugin does:

* It applies the `java` plugin if not already applied.  That plugin provides the compile/build lifecycle tasks.
* This plugin creates the `xray` task and makes `test` depend on it.  That causes the XRay tests to be run before the `test` task.

This plugin accepts several configuration parameters, as listed below.

### Customization

To customize the XRay runner, set the following properties appropriately in `gradle.properties`,
or `$HOME/.gradle/gradle-properties`, or as `-Pname=value` parameters on the Gradle command line:

```$properties
xray.scheme=http
xray.hostname=localhost
xray.port=1234
xray.path=/xray/
xray.user=
xray.password=
xray.basic-auth=false
xray.quiet=true
xray.outputXUnit=true
```

It is also possible to set the XRay query parameters (dir, module, etc) in the `build.gradle` file
(but not as properties) by explicitly declaring the `xray` task.  Such as:

```$groovy
xray {
	parameters = [dir: 'xray-tests']
	quiet = false
}
```

### Help

This and other information can be printed out by running the `xray-help` task.

### XRay Installation

This plugin invokes the XRay XQuery unit testing framework, which must be installed and invokable on an
HTTP appserver.  The default URL is `http://localhost:1234/xray/` (see properties below).  See the
XRay project for details and installation instructions:

> [https://github.com/robwhitby/xray](https://github.com/robwhitby/xray)

### Contact

Ron Hitchens ([ron@overstory.co.uk](mailto:ron@overstory.co.uk), [@ronhitchens](https://twitter.com/ronhitchens)))<br/>
OverStory Ltd ([overstory.co.uk](http://overstory.co.uk), [info@overstory.co.uk](mailto:info@overstory.co.uk), [@overstory](https://twitter.com/overstory))<br/>
Gradle.org ([plugins.gradle.org](https://plugins.gradle.org/plugin/uk.co.overstory.xray))<br/>
GitHub ([github.com/overstory](https://github.com/overstory/xray-gradle-plugin))<br/>
