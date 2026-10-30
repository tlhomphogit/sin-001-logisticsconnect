.DEFAULT_GOAL := help
.NOTPARALLEL:
.PHONY: help build test up stop restart status logs

help:
	@printf '%s\n' \
	  'make up       Build missing JARs and start the broker and four services' \
	  'make status   Show tracked services and HTTP health' \
	  'make logs     Follow service logs; Ctrl+C exits the log viewer' \
	  'make stop     Stop tracked JAR processes; leave the shared broker running' \
	  'make restart  Stop and start the JAR processes' \
	  'make build    Package all four services without running tests' \
	  'make test     Run clean verify for all four services'

build test up stop restart status logs:
	@bash scripts/dev.sh $@