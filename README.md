**PassiveWhitelist** is a lightweight plugin for semi-private Minecraft servers, such as school servers, friend groups, or small communities. New players must answer a question when they join, so you can control who gets access without adding every player to a whitelist in advance. Players with the right answer are verified permanently and can play on the server. The question, answer, time limit, and messages are all customizable.

## Configuration
* The plugin creates a config file at plugins/PassiveWhitelist/config.yml.
* Default settings:

```yaml
answer: "bunubbv"
question: "<aqua>Who is hosting our Minecraft server?</aqua>"
welcomeMessage: "<yellow>Welcome! Please answer the question below to start playing.</yellow>"
correctMessage: "<green>Correct! You're now verified and ready to play.</green>"
incorrectMessage: "<red>Incorrect answer. Please try again.</red>"
kickMessage: "<red>You didn't answer in time. Please try again later.</red>"
kickDelay: 3 # minute(s)
version: 1 # DO NOT EDIT
```
 
* After editing the config, run /psw reload to apply changes without restarting the server.

## Permissions
* psw.bypass:
  * default: op
* psw.reload:
  * default: op
* psw.revoke:
  * default: op

## Commands
* /psw reload
* /psw revoke \<username>
* /psw bypass \<username>
