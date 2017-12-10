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
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException

class XRayTask extends DefaultTask
{
	public String scheme = getPropertyWithDefault ('xray.scheme', 'http')
	public String host = getPropertyWithDefault ('xray.hostname', 'localhost')
	public int port = Integer.parseInt (getPropertyWithDefault ('xray.port', '1234'))
	public String path = getPropertyWithDefault ('xray.path', '/xray')// the path to invoke xray/index.xqy on the appserver
	public String user = getPropertyWithDefault ('xray.user', null)
	public String password = getPropertyWithDefault ('xray.password', null)
	public boolean basicAuth = Boolean.parseBoolean (getPropertyWithDefault ('xray.basic-auth', 'false'))
	public boolean quiet = Boolean.parseBoolean (getPropertyWithDefault ('xray.quiet', 'false'))// set true to suppress passing tests
	public Map<String, String> parameters = [:]        // XRay query params for dir, module, etc.  Not settable by properties.  Format is always forced to 'xml'.
	public boolean outputXUnit = Boolean.parseBoolean (getPropertyWithDefault ('xray.output-xunit', 'true'))        // set false to suppress JUnit-style output

	static String markLogicErrorNS = 'http://marklogic.com/xdmp/error'
	private static final Map<String, String> colors = [failed: '\033[31m', error: '\033[1;31m', passed: '\033[32m', ignored: '\033[33m']

	@TaskAction
	void runXRay()
	{
		try {
			long startTime = System.currentTimeMillis()
			GPathResult results = invokeXrayTests()

			printResults (results, quiet, colors, startTime)
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

		println "XRay tests starting on ${uriString}"

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

	private void printResults (GPathResult xml, boolean quiet, Map<String, String> colors, long startTime)
	{
		int pass = 0
		int ignore = 0
		int fail = 0
		int error = 0
		int total = 0

		xml.module.each {
			total += Integer.parseInt (it.'@total'.text ())
			pass += Integer.parseInt (it.'@passed'.text ())
			int i = Integer.parseInt (it.'@ignored'.text ())
			ignore += i
			int f = Integer.parseInt (it.'@failed'.text ())
			fail += f
			int e = Integer.parseInt (it.'@error'.text ())
			error += e

			boolean printModuleInfo = (!quiet) || ((e + f + i) != 0)

			if (printModuleInfo) {
				println "Module: ${it.'@path'.text ()}, total=${it.'@total'.text ()}, pass=${it.'@passed'.text ()}, fail=${it.'@failed'.text ()}, error=${it.'@error'.text ()}, ignore=${it.'@ignored'.text ()}"
			}

			it.test.each {
				it.declareNamespace (error: markLogicErrorNS)

				String result = it.'@result'.text ()
				boolean printTestDetail = (!quiet) || (!"passed".equals (result))

				if (printTestDetail) println " ${colors [result]}${result.toUpperCase ()}: ${it.'@name'.text ()}\033[0m (${it.'@time'.text ()})"

				if ('failed'.equals (result)) {
					println "     assert: ${it.assert.'@test'.text ()}"
					println "     actual: ${it.assert.actual.text ()}"
					println "   expected: ${it.assert.expected.text ()}"
					println "    message: ${it.assert.message.text ()}"
				}

				if ('error'.equals (result)) {
					if (it.'error:error'.'error:message') {
						println "  Message: ${it.'error:error'.'error:message'.text ()}, see xUnit output for stack trace"
					} else {
						println "  Unknown error, see xUnit output for details"
					}
				}
			}

			if (outputXUnit) this.writeXUnit (it)
		}

		double seconds = (double) (System.currentTimeMillis () - startTime) / 1000.0
		int notPassed = error + fail
		String color = (notPassed != 0) ? colors ['failed'] : (ignore != 0) ? colors ['ignored'] : colors ['passed']

		println "XRay results: ${color}total=${total}, pass=${pass}, fail=${fail}, error=${error}, ignore=${ignore}\033[0m, elapsed ${seconds} seconds"

		if (notPassed > 0) {
			throw new TaskExecutionException (this, new RuntimeException ("There ${(notPassed == 1) ? 'was' : 'were'} ${notPassed} XRay test failure${(notPassed == 1) ? '' : 's'}"))
		}
	}

	private final String testFilePathRoot = "${project.buildDir.path}/test-results/test"
	private final File testsDir = new File (testFilePathRoot)

	void writeXUnit (GPathResult m)
	{
		if (!testsDir.exists()) {
			if (!testsDir.mkdirs()) {
				throw new TaskExecutionException (this, new RuntimeException ("Cannot create xUnit test output directory: ${testsDir.path}"))
			}
		}

		StreamingMarkupBuilder builder = new StreamingMarkupBuilder ()
		builder.encoding = 'UTF-8'

		def xml = builder.bind {
			mkp.declareNamespace (error: markLogicErrorNS)

			it.testsuite (name: m.'@path'.text (), classname: m.'@path'.text (), tests: m.'@total'.text (), errors: m.'@error'.text (), failures: m.'@failed'.text (), skipped: m.'@ignored'.text ()) {
				m.test.each { GPathResult t ->
					t.declareNamespace (error: markLogicErrorNS)

					it.testcase (name: t.'@name'.text (), time: t.'@time'.text ()) {
						switch (t.'@result'.text ()) {
							case 'passed':
								break
							case 'ignored':
								skipped ()
								break
							case 'error':
								error (message: t.'error:error'.'error:message'.text ()) {
									mkp.yieldUnescaped (builder.bindNode (t.'*').toString ())
								}
								break
							case 'failed':
								failure (type: t.assert.'@test'.text (), "expected: ${t.assert.expected.text ()}, actual: ${t.assert.actual.text ()}, message: ${t.assert.message.text ()}".toString ())
								break
						}
					}
				}
			}
		}

		String testFilename = "${testFilePathRoot}/TEST-${m.'@path'.text ().substring (1).replaceAll ('/|\\\\', '_')}.xml"
		FileOutputStream stream = new FileOutputStream (new File (testFilename))

		stream << XmlUtil.serialize (xml)

		stream.close()
	}

	private String getPropertyWithDefault (String propName, String defaultValue)
	{
		(project.hasProperty (propName)) ? project.properties [propName] : defaultValue
	}
}
