VERSION ?= dev

.PHONY: build test test-e2e test-scripts lint tidy vet compose-config

build:
	go build -ldflags "-X main.version=$(VERSION)" -o bin/tunneld ./cmd/tunneld

test:
	go test ./...

test-e2e:
	go test -tags=e2e -timeout 20m ./e2e/...

test-scripts:
	sh deploy/scripts/scripts_test.sh

lint:
	golangci-lint run
	shellcheck deploy/scripts/*.sh
	$(MAKE) compose-config

# compose-config validates the deployment stack; --env-file supplies placeholder values so a bare
# checkout / CI (no .env) does not fail the ${DEPLOY_UID:?} interpolation.
compose-config:
	docker compose --env-file deploy/.env.example -f deploy/docker-compose.yml config -q

tidy:
	go mod tidy

vet:
	go vet ./...
