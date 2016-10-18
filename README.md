# deploylib2

This is a stripped down implementation, influenced by https://github.com/radlab/deploylib. At a high level, it is a scala DSL for manipulating a 'machine' (represented by an IP address and an SSH key).

## Requirements
- sbt

## Preparing a machine
- Create a user that can sudo without a password by adding that user to /etc/sudoers: <username> ALL=(ALL) NOPASSWD: ALL 
