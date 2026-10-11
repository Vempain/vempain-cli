package fi.poltsi.vempain.cli.core;

import org.json.JSONObject;

public final class BackendVersionValidator {
	private BackendVersionValidator() {
	}

	public static void validate(JSONObject response) {
		var backendVersion = response.optString("version", "")
		                             .trim();
		if (backendVersion.isBlank()) {
			throw new IllegalStateException("Backend version response did not include a version");
		}

		var cliVersion = CliVersion.current();
		if (!cliVersion.equals(backendVersion)) {
			throw new IllegalStateException("Incompatible backend version " + backendVersion + "; this CLI requires version " + cliVersion
											+ ". Upgrade the CLI or backend so their versions match.");
		}
	}
}
