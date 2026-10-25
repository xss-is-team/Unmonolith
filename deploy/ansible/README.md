# VPS automation for Java Blueprints (https://unmonolith.sh9.top)

Ubuntu/Debian VDS: base hardening, Docker, host Caddy (automatic HTTPS), and the Java Blueprints stack
(`../prod/compose.yaml`, images from Docker Hub). Node/Go/uv (`playbooks/runtime.yml`) are not part of `site.yml`.

1. `group_vars/vps.yml`: set `expected_public_ip` and `dockerhub_username`. `inventory.ini`: host, user, SSH key
   (the file still holds the values of the previous project).
2. Bootstrap once, as a sudo-capable user `deploy` that exists on the VDS: `just site`
   (base -> docker -> caddy -> app). Private Docker Hub repositories: `export DOCKERHUB_TOKEN=...` first.
3. Later releases are rolled out by `.github/workflows/deploy.yml`; `just app` re-applies the stack by hand.

DNS `A unmonolith.sh9.top -> VDS IP` must exist before Caddy can obtain a certificate.
The web container is published on 127.0.0.1:3001 only (Docker would otherwise bypass UFW).

## Ubuntu 25.10+ (sudo-rs)
The default `sudo` there is `sudo-rs`, whose password prompt Ansible does not recognise ("Timeout waiting for privilege
escalation prompt"). Either allow passwordless sudo for the deploy user
(`echo "deploy ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/deploy`) and run `ansible-playbook playbooks/site.yml`,
or switch back to the classic one: `sudo update-alternatives --set sudo /usr/bin/sudo.ws`.
