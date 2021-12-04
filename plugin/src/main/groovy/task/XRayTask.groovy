/*
 * Copyright 2013-2017 OverStory Ltd <copyright@overstory.co.uk> and other contributors
 * (see the CONTRIBUTORS file).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package task

/**
 * Created by IntelliJ IDEA.
 * User: ron
 * Date: 12/8/17
 * Time: 11:42 PM
 */
import groovy.util.slurpersupport.GPathResult
import groovy.xml.StreamingMarkupBuilder
import groovy.xml.XmlUtil
import groovyx.net.http.FromServer
import groovyx.net.http.HttpBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class XRayTask extends DefaultTask
{
	@Input String scheme = getPropertyWithDefault ('xray.scheme', 'http')
	@Input String host = getPropertyWithDefault ('xray.hostname', 'localhost')
	@Input int port = Integer.parseInt (getPropertyWithDefault('xray.port', '1234'))
	@Input String path = getPropertyWithDefault ('xray.path', '/xray/')	// the path to invoke xray/index.xqy on the appserver
	@Optional @Input String user = getPropertyWithDefault ('xray.user', null)
	@Optional @Input String password = getPropertyWithDefault ('xray.password', null)

	@Input boolean basicAuth = Boolean.parseBoolean (getPropertyWithDefault ('xray.basic-auth', 'false'))
	@Internal boolean getBasicAuth = this.basicAuth 	// Gradle 7 requires that there are not both isXxx and getXxx available to it, so we need to make one internal

	@Input boolean quiet = Boolean.parseBoolean (getPropertyWithDefault ('xray.quiet', 'false'))// set true to suppress passing tests
	@Internal boolean getQuiet = this.quiet

	@Input Map<String, String> parameters = [:]	// XRay query params for dir, module, etc.  Not settable by properties.  Format is always forced to 'xml'.

	@Input boolean outputXUnit = Boolean.parseBoolean (getPropertyWithDefault ('xray.output-xunit', 'true'))	// set false to suppress JUnit-style output
	@Internal boolean getOutputXUnit = this.outputXUnit

	@Internal final String timestamp = LocalDateTime.now().format (DateTimeFormatter.ISO_LOCAL_DATE_TIME)
	static final String hostName = InetAddress.localHost.getHostName()
	static final String markLogicErrorNS = 'http://marklogic.com/xdmp/error'
	static final Map<String, String> colors = [failed: '\033[31m', error: '\033[1;31m', passed: '\033[32m', ignored: '\033[33m']

	@TaskAction
	void runXRay()
	{
		try {
			long startTime = System.currentTimeMillis()
			GPathResult results = invokeXrayTests()

			printResults (results, quiet, startTime)
		} catch (TaskExecutionException e) {
			throw e        // pass through, thrown from printResults if there were failures
		} catch (Exception e) {
			println "XRay: Unexpected exception invoking XRay tests: ${e}"
			throw new TaskExecutionException (this, e)
		}
	}

	private GPathResult invokeXrayTests()
	{
		String uriString = "${scheme}://${host}:${port}${path}"

		println "Running XRay tests on ${uriString}"

		HttpBuilder http = HttpBuilder.configure {
			request.uri = uriString

			if (user != null) {
				if (basicAuth) {
					request.auth.basic (user, password)
				} else {
					request.auth.digest (user, password)
				}
			}
		}

		return http.get {
			parameters ['format'] = 'xml'
			request.uri.query = parameters

			response.success { FromServer fs, Object body ->
				body
			}

			response.failure { FromServer fs ->
				String msg = "Unexpected error response from XRay tests: ${fs.statusCode}"
				println msg
				throw new RuntimeException (msg)
			}
		} as GPathResult
	}

	private void printResults (GPathResult xml, boolean quiet, long startTime)
	{
		int pass = 0
		int ignore = 0
		int fail = 0
		int error = 0
		int total = 0

		xml.module.each { GPathResult m ->
			total += Integer.parseInt (m.'@total'.text())
			pass += Integer.parseInt (m.'@passed'.text())
			int i = Integer.parseInt (m.'@ignored'.text())
			ignore += i
			int f = Integer.parseInt (m.'@failed'.text())
			fail += f
			int e = Integer.parseInt (m.'@error'.text())
			error += e

			boolean printModuleInfo = ( ! quiet) || ((e + f + i) != 0)

			if (printModuleInfo) {
				println "Module: ${m.'@path'.text()}, total=${m.'@total'.text()}, pass=${m.'@passed'.text()}, fail=${m.'@failed'.text()}, error=${m.'@error'.text()}, ignore=${m.'@ignored'.text()}"
			}

			m.test.each { GPathResult t ->
				t.declareNamespace (error: markLogicErrorNS)

				String result = t.'@result'.text()
				boolean printTestDetail = (!quiet) || (!"passed".equals(result))

				if (printTestDetail) println "  ${colors[result]}${result.toUpperCase()}: ${t.'@name'.text()}\033[0m (${t.'@time'.text()})"

				if ('failed'.equals (result)) {
					println "     assert: ${t.assert.'@test'.text()}"
					println "     actual: ${t.assert.actual.text()}"
					println "   expected: ${t.assert.expected.text()}"
					println "    message: ${t.assert.message.text()}"
				}

				if ('error'.equals (result)) {
					String xUnitMessage = (outputXUnit) ? ", see xUnit output for details: ${testFileName (m.'@path'.text())}".toString() : ''

					if (t.'error:error'.'error:message') {
						println "    Message: ${t.'error:error'.'error:message'.text()}, type: ${t.'error:error'.'error:name'.text()}${xUnitMessage}"
					} else {
						println "    Unknown error${xUnitMessage}"
					}
				}
			}

			if (outputXUnit) this.writeXUnit (m)
		}

		double seconds = (double) (System.currentTimeMillis() - startTime) / 1000.0
		int notPassed = error + fail
		String color = (notPassed != 0) ? colors['failed'] : (ignore != 0) ? colors['ignored'] : colors['passed']

		println "XRay results: ${color}total=${total}, pass=${pass}, fail=${fail}, error=${error}, ignore=${ignore}\033[0m, ${seconds} seconds"

		if (notPassed > 0) {
			boolean singular = notPassed == 1

			throw new TaskExecutionException (this, new RuntimeException ("There ${(singular) ? 'was' : 'were'} ${notPassed} XRay test failure${(singular) ? '' : 's'}"))
		}
	}

	private final String testFilePathRoot = "${project.buildDir.path}/test-results/test"
	private final File testsDir = new File (testFilePathRoot)

	void writeXUnit (GPathResult m)
	{
		if ( ! testsDir.exists()) {
			if ( ! testsDir.mkdirs()) {
				throw new TaskExecutionException (this, new RuntimeException ("Cannot create xUnit test output directory: ${testsDir.path}"))
			}
		}

		StreamingMarkupBuilder builder = new StreamingMarkupBuilder()
		builder.encoding = 'UTF-8'

		def xml = builder.bind {
			mkp.declareNamespace (error: markLogicErrorNS)

			String suiteName = 'xray.' + m.'@path'.text().replace ('.', '_').replace ('-', '_').replace ('/', '.').substring (1)
			String suiteTimeTotal = sumTestTimes (m)

			it.testsuite (name: suiteName, tests: m.'@total'.text(), errors: m.'@error'.text(), failures: m.'@failed'.text(), skipped: m.'@ignored'.text(), hostname: hostName, time: suiteTimeTotal, timestamp: timestamp) {
				m.test.each { GPathResult t ->
					t.declareNamespace (error: markLogicErrorNS)

					String timeSecs = t.'@time'.text().replaceFirst ('^PT(.+)S$', '$1')

					it.testcase (name: t.'@name'.text(), time: timeSecs) {
						switch (t.'@result'.text()) {
						case 'passed':
							break
						case 'ignored':
							skipped()
							break
						case 'error':
							error (message: t.'error:error'.'error:message'.text(), type: t.'error:error'.'error:name'.text()) {
								mkp.yieldUnescaped (builder.bindNode (t.'*').toString())
							}
							break
						case 'failed':
							failure (type: t.assert.'@test'.text(), message: t.assert.message.text(), "expected: ${t.assert.expected.text()}, actual: ${t.assert.actual.text()}, message: ${t.assert.message.text()}".toString())
							break
						}
					}
				}
			}
		}

		FileOutputStream stream = new FileOutputStream (new File (testFileName (m.'@path'.text())))

		stream << XmlUtil.serialize (xml)

		stream.close()
	}

	String testFileName (String path)
	{
		"${testFilePathRoot}/TEST-${path.substring (1).replaceAll ('/|\\\\', '_')}.xml"

	}

	static String sumTestTimes (GPathResult module)
	{
		double sum = 0.0

		module.test.each { GPathResult t ->
			sum += Double.parseDouble (t.'@time'.text().replaceFirst ('^PT(.+)S$', '$1'))
		}

		sum
	}

	private String getPropertyWithDefault (String propName, String defaultValue)
	{
		(project.hasProperty (propName)) ? project.properties [propName] : defaultValue
	}
}
