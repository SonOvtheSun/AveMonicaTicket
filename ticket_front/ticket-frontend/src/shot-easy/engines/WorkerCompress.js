import { Queue } from './Queue';
import { convert } from './handler';
import { avifCheck } from './support';

const queue = new Queue(3);

/**
 * 关键点：
 * 1. 必须先注册 message 监听，不能等 avifCheck 完成后再监听。
 * 2. avifCheck 放到任务内部 await，避免首次 postMessage 丢失。
 * 3. 压缩失败时也要 postMessage 返回 error，否则前端 Promise 会一直 pending。
 */
const readyPromise = avifCheck().catch((error) => {
    console.warn('avifCheck failed, continue without AVIF canvas support:', error);
});

globalThis.addEventListener('message', (event) => {
    const key = event.data?.info?.key;

    queue.push(async () => {
        try {
            await readyPromise;

            const output = await convert(event.data, 'compress');

            if (output) {
                globalThis.postMessage(output);
                return;
            }

            globalThis.postMessage({
                key,
                error: '不支持的图片格式或压缩结果为空',
            });
        } catch (error) {
            console.error('compress worker error:', error);

            globalThis.postMessage({
                key,
                error: error?.message || '图片压缩失败',
            });
        }
    });
});