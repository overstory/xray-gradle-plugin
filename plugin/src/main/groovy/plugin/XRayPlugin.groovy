package plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
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
		project.tasks.create ('xray', XRayTask) { XRayTask task ->
			task.group = 'verification'
			task.description = 'Run XRay tests on MarkLogic'
		}
	}
}
