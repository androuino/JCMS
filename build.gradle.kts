plugins {
	java
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

repositories {
	mavenCentral()
}

dependencies {
	testImplementation("junit:junit:4.13.1")
}

// Task to generate JNI headers
tasks.register<Exec>("headers") {
	description = "Generates JNI .h header files"

	val javaFile = file("src/main/java/com/gmail/etordera/jcms/JCMS.java")
	val distFile = file("src/jni/cpp/com_gmail_etordera_jcms_JCMS.h")

	inputs.file(javaFile)
	outputs.file(distFile)

	commandLine(
			"javah",
			"-J-Dfile.encoding=UTF-8",
			"-classpath", "src/main/java",
			"-d", "src/jni/cpp",
			"com.gmail.etordera.jcms.JCMS"
	)
}

data class CompilerConfig(
	val libOs: String,
	val libPrefix: String,
	val libSuffix: String,
	val compilerFlags: String,
	val compilerInclude: String,
	val compilerInclude2: String
)

// Task to compile JNI shared object
tasks.register<Exec>("jni") {
	description = "Compiles JNI shared object which will allow access to native libraries."
	dependsOn(tasks.named("headers"))

	val jdkHome = file(System.getProperty("java.home")).parentFile
	println("JDK Home: $jdkHome")

	val osArch = System.getProperty("os.arch")
	val libArch = if (osArch.contains("64")) "64" else "32"

	val osName = System.getProperty("os.name").toLowerCase()
	val config = when {
		osName.contains("linux") -> CompilerConfig(
			libOs = "linux",
			libPrefix = "lib",
			libSuffix = ".so",
			compilerFlags = "-fPIC",
			compilerInclude = "-I$jdkHome/java-11-openjdk-amd64/include",
			compilerInclude2 = "-I$jdkHome/java-11-openjdk-amd64/include/linux"
		)
		osName.contains("windows") -> CompilerConfig(
			libOs = "windows",
			libPrefix = "libwin",
			libSuffix = ".dll",
			compilerFlags = "-Wl,--add-stdcall-alias",
			compilerInclude = "-I$jdkHome/include",
			compilerInclude2 = "-I$jdkHome/include/win32"
		)
		osName.contains("mac") || osName.contains("darwin") -> CompilerConfig(
			libOs = "mac",
			libPrefix = "libmac",
			libSuffix = ".dylib",
			compilerFlags = "-fPIC",
			compilerInclude = "-I$jdkHome/Home/include",
			compilerInclude2 = "-I$jdkHome/Home/include/darwin"
		)
		else -> throw IllegalArgumentException("Unsupported OS: $osName")
	}

	val (libOs, libPrefix, libSuffix, compilerFlags, compilerInclude, compilerInclude2) = config

	val libMainDir = "src/main/resources/com/gmail/etordera/jcms/lib"
	val libDir = "$libMainDir/$libOs$libArch"
	val libPath = "$libDir/$libPrefix" + "jcms" + libSuffix

	inputs.dir("src/jni/cpp")
	outputs.file(file(libPath))

	doFirst {
		file(libDir).mkdirs()
	}

	println("compilerInclude: $compilerInclude")
	println("compilerInclude2: $compilerInclude2")

	executable = "g++"
	args(
		compilerFlags,
		"-shared",
		"-o", libPath,
		compilerInclude,
		compilerInclude2,
		"-I/usr/include/lcms2", // Include path for lcms2
		"src/jni/cpp/jcms.cpp",
		"-llcms2"
	)
}

tasks.named<ProcessResources>("processResources") {
	dependsOn(tasks.named("jni"))
}

tasks.register<Zip>("zipsrc") {
	from("src/main/java")
	include("**/*.java")
	archiveFileName.set("src.zip")
	destinationDirectory.set(file("build"))
}

tasks.named<Delete>("clean") {
	doLast {
		delete(tasks.named("headers").get().outputs.files.singleFile)
		delete(tasks.named("jni").get().outputs.files)
	}
}

tasks.test {
	// Set the java.library.path system property to the directory where the .so file is generated
	systemProperty("java.library.path", file("src/main/resources/com/gmail/etordera/jcms/lib").absolutePath)
}
