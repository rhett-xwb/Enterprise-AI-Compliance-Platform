import http from 'node:http';
// Deliberately deterministic. This validates transport/guards, not LLM reasoning.
http.createServer(async (req, res) => {
  if (req.url === '/health') { res.end('UP'); return; }
  if (req.url !== '/v1/chat/completions' || req.method !== 'POST') { res.writeHead(404).end(); return; }
  try {
    let text = ''; for await (const chunk of req) { text += chunk; if (text.length > 20000) throw Error(); }
    const body = JSON.parse(text), input = JSON.parse(body.messages[1].content);
    if (input.question.includes('失败测试')) { res.writeHead(429).end('{}'); return; }
    let result = { status: 'INSUFFICIENT', answer: '', citations: [] };
    if (input.question.includes('伪造引用')) result = { status: 'ANSWERED', answer: '伪造结论[999]', citations: [999] };
    else if (input.question.includes('冲突测试') && input.evidence.length >= 2) result = { status: 'CONFLICT', answer: '两条依据需要法务核对。[1][2]', citations: [1, 2] };
    else if (!input.question.includes('无答案测试')) {
      const evidence = input.evidence.find(e => e.text.includes('审批申请'));
      if (evidence) result = { status: 'ANSWERED', answer: `出差前需要提交审批申请。[${evidence.id}]`, citations: [evidence.id] };
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: JSON.stringify(result) } }] }));
  } catch { res.writeHead(400).end('{}'); }
}).listen(Number(process.env.QA_TEST_PORT), '127.0.0.1');
