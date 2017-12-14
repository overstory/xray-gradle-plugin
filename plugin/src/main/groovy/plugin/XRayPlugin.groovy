package plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import task.XRayTask

/**
 * Created by IntelliJ IDEA.
 * User: ron
 * Date: 12/8/17
 * Time: 10:14 PM
 */
class XRayPlugin implements Plugin<Project>
{
	@Override
	void apply (Project project)
	{
		if ( ! project.pluginManager.hasPlugin ('java')) {
			project.pluginManager.apply ('java')
		}

		project.tasks.create ('xray', XRayTask) { XRayTask xray ->
			xray.group = 'verification'
			xray.description = "Runs XRay tests on MarkLogic: ${xray.scheme}://${xray.host}:${xray.port}${xray.path}\""

			project.afterEvaluate { Project proj ->
				Task test = proj.getTasks().findByPath ('test')

				if (test) {
					test.dependsOn (xray)
				}
			}
		}

		project.tasks.create ('xray-help') { Task task ->
			task.group = 'documentation'
			task.description = 'Help text for the XRay plugin'

			task.doLast {
				println helpText
			}
		}
	}

	private static final String helpText =
"""This Gradle plugin is for running MarkLogic XRay tests along with the Java/Groovy 'test' task.

To use this, the XRay XQuery unit testing framework must be installed and invokable on an
HTTP appserver.  The default is http://localhost:1234/xray/ (see properties below).  See the
XRay project for details:

\thttps://github.com/robwhitby/xray

In the build.gradle file, add the following (or add to your existing plugins{} section:

\tplugins {
\t\tid uk.co.overstory.xray
\t}

That's it.  The XRay plugin will apply the 'java' plugin if it is not already present, to define
the 'test' build task.  It will also set itself up a dependency for 'test' so that it will always
run before any other unit tests.

You can also just run the 'xray' task directly.

This will run the XRay tests with defaults, or the property settings found in scope.  To customize
the XRay runner, set the following properties appropriately in gradle.properties, or \$HOME/.gradle/gradle-properties,
or as -Pname=value parameters on the Gradle command line:

\txray.scheme=http
\txray.hostname=localhost
\txray.port=1234
\txray.path=/xray/
\txray.user=
\txray.password=
\txray.quiet=true
\txray.basic-auth=false
\txray.outputXUnit=true

These are the defaults (user and password are empty by default).  If user/password are supplied, then HTTP digest
credentials are applied to the request (or basic credentials if basic-auth is true).

You can also specify settings directly in your build.gradle by configuring the task like this.

\txray {
\t\thost = 'dev.mycompany.com'
\t\tport = 7890
\t\tparameters = [dir: 'mytests']
\t\tquiet = true
\t}

Created December 2017 by Ron Hitchens (ron@overstory.co.uk, @ronhitchens, @overstory, http://overstory.co.uk)

"""
}
