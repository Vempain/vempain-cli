Name:           vempain-file-cli
Version:        0.0.0
Release:        1%{?dist}
Summary:        Vempain File backend command-line client

License:        GPL-2.0-only
URL:            https://github.com/Vempain/vempain-cli
Source0:        %{name}.tar.gz

BuildArch:      noarch
Requires:       java-25-openjdk-headless

%description
Command-line utility for the Vempain File backend API.

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

