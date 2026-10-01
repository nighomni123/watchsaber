#!/bin/bash
# Thin wrapper so every call uses the same project-local monid config.
# AGENTS.md rule: web research goes through monid only, never the built-in search.
export XDG_CONFIG_HOME="/Users/Shared/AndroidProjects/Lightsaber/.monid/xdg"
exec "/Users/Mitesh Gada/.npm-global/bin/monid" "$@"
