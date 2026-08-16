VERSION ?= dev

.PHONY: build test test-e2e lint tidy vet

build:
	go build -ldflags "-X main.version=$(VERSION)" -o bin/tunneld ./cmd/tunneld

test:
	go test ./...

test-e2e:
	go test -tags=e2e -timeout 20m ./e2e/...

lint:
	golangci-lint run

tidy:
	go mod tidy

vet:
	go vet ./...
