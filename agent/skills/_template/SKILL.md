---
name: _template
description: 技能模板 — 复制本目录并修改
trigger: [示例触发词]
tools: [read_file_content]
version: "1"
---
在这里写技能指令正文：告诉 AI 该技能何时使用、如何执行、输出什么格式。

- 指令会注入到触发时的工作 agent 系统提示词
- trigger 命中用户消息（大小写不敏感包含匹配）即激活
- tools 为允许调用的内置工具白名单（云盘技能仅此能力，不执行代码）
- 全局技能可在本目录放 tools.py，用 @tool / @safe_tool 定义可执行工具
