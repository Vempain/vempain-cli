package fi.poltsi.vempain.cli.core;

public final class CliVersion {
	private static final String DEVELOPMENT_VERSION = "0.0.1-SNAPSHOT";

	private CliVersion() {
	}

	public static String current() {
		var implementationVersion = CliVersion.class.getPackage()
													.getImplementationVersion();
		return implementationVersion == null || implementationVersion.isBlank()
			   ? DEVELOPMENT_VERSION
			   : implementationVersion;
	}
}
