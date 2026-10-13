package fi.poltsi.vempain.cli.core;

public final class CliVersion {
	private static final String DEVELOPMENT_VERSION = "0.0.1-SNAPSHOT";

	private CliVersion() {
	}

	public static String current() {
		return orDevelopment(CliVersion.class.getPackage()
											 .getImplementationVersion());
	}

	/**
	 * @return the manifest version, or the development version when the CLI does not run from a versioned jar
	 */
	static String orDevelopment(String implementationVersion) {
		return implementationVersion == null || implementationVersion.isBlank()
			   ? DEVELOPMENT_VERSION
			   : implementationVersion;
	}
}
