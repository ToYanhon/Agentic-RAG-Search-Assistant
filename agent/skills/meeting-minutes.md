---
name: meeting-minutes
description: 把对话或文档整理成标准会议纪要
trigger: [会议纪要, 会议记录, meeting minutes]
tools: [read_file_content, semantic_search]
version: "1"
---
当用户要求整理会议纪要时，按以下结构输出：

1. **会议主题**：一句话概括
2. **与会要点**：分点列出关键讨论内容
3. **决议事项**：明确达成的决定
4. **待办清单**：事项 + 负责人 + 截止时间（如可推断）
5. **遗留问题**：未决事项

要求：内容忠实于原始材料，不补充材料中没有的信息；用中文 markdown 输出。
