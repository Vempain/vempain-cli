Name:           vempain-file-cli
Version:        0.0.0
Release:        1%{?dist}
Summary:        Vempain command-line client for the file and admin backends

License:        GPL-2.0-only
URL:            https://github.com/Vempain/vempain-cli
Source0:        %{name}.tar.gz

BuildArch:      noarch
Requires:       java-25-openjdk-headless

%description
Command-line client (vf-cli) for the Vempain File and Vempain Admin backends.
The package keeps its historical name; it is not limited to the file backend.

%prep
%setup -q

%build
# No build step here. The reusable workflow injects a prebuilt fat jar
# into packaging/rpm/vf-cli.jar before rpmbuild runs.

%install
install -d %{buildroot}/usr/bin
install -d %{buildroot}/usr/lib/vempain/file

install -m 0755 vf-cli %{buildroot}/usr/bin/vf-cli
install -m 0644 packaging/rpm/vf-cli.jar %{buildroot}/usr/lib/vempain/file/vf-cli.jar

%files
%license LICENSE
/usr/bin/vf-cli
/usr/lib/vempain/file/vf-cli.jar

%changelog
* Fri May 15 2026 Vempain CI
- Initial RPM packaging for vf-cli

