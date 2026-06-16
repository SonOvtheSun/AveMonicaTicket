// src/shot-easy/compressImage.jsx

import WorkerCompress from './engines/WorkerCompress?worker';
import { Mimes } from './lib/mimes';
import { AvifImage } from './engines/AvifImage';

const DefaultCompressOption = {
    preview: {
        maxSize: 256,
    },
    resize: {
        method: undefined,
        width: undefined,
        height: undefined,
    },
    format: {
        target: undefined,
        transparentFill: '#FFFFFF',
    },
    jpeg: {
        quality: 0.7,
    },
    png: {
        colors: 32,
        dithering: 0,
    },
    gif: {
        colors: 128,
        dithering: false,
    },
    avif: {
        quality: 50,
        speed: 8,
    },
};

let worker = null;
let taskId = 0;
const taskMap = new Map();

function getWorker() {
    if (!worker) {
        worker = new WorkerCompress();

        worker.addEventListener('message', async (event) => {
            const data = event.data;
            const task = taskMap.get(data.key);

            if (!task) return;

            taskMap.delete(data.key);

            try {
                let compress = data.compress;

                // SVG 如果需要转 webp/jpg/avif，需要在主线程再转一次
                if (
                    task.file.type === Mimes.svg &&
                    compress &&
                    task.option.format.target
                ) {
                    compress = await convertSvgToTarget(
                        compress,
                        data.width,
                        data.height,
                        task.option
                    );
                }

                if (!compress?.blob) {
                    task.resolve(task.file);
                    return;
                }

                const outputFile = blobToFile(
                    compress.blob,
                    task.file.name,
                    task.option.format.target
                );

                task.resolve(outputFile);
            } catch (error) {
                task.reject(error);
            }
        });

        worker.addEventListener('error', (error) => {
            console.error('shot-easy compress worker error:', error);
        });
    }

    return worker;
}

function createKey() {
    taskId += 1;
    return `shot-easy-${Date.now()}-${taskId}`;
}

function mergeOption(option = {}) {
    return {
        ...DefaultCompressOption,
        ...option,
        preview: {
            ...DefaultCompressOption.preview,
            ...(option.preview || {}),
        },
        resize: {
            ...DefaultCompressOption.resize,
            ...(option.resize || {}),
        },
        format: {
            ...DefaultCompressOption.format,
            ...(option.format || {}),
        },
        jpeg: {
            ...DefaultCompressOption.jpeg,
            ...(option.jpeg || {}),
        },
        png: {
            ...DefaultCompressOption.png,
            ...(option.png || {}),
        },
        gif: {
            ...DefaultCompressOption.gif,
            ...(option.gif || {}),
        },
        avif: {
            ...DefaultCompressOption.avif,
            ...(option.avif || {}),
        },
    };
}

function getOutputExt(target, blobType, originalName) {
    if (target) {
        if (target === 'jpg') return 'jpg';
        if (target === 'jpeg') return 'jpg';
        if (target === 'png') return 'png';
        if (target === 'webp') return 'webp';
        if (target === 'gif') return 'gif';
        if (target === 'avif') return 'avif';
        if (target === 'svg') return 'svg';
    }

    switch (blobType) {
        case 'image/jpeg':
            return 'jpg';
        case 'image/png':
            return 'png';
        case 'image/webp':
            return 'webp';
        case 'image/gif':
            return 'gif';
        case 'image/avif':
            return 'avif';
        case 'image/svg+xml':
            return 'svg';
        default: {
            const matched = originalName?.match(/\.([^.]+)$/);
            return matched ? matched[1] : 'jpg';
        }
    }
}

function replaceExt(name, ext) {
    if (!name) return `image.${ext}`;

    if (name.includes('.')) {
        return name.replace(/\.[^.]+$/, `.${ext}`);
    }

    return `${name}.${ext}`;
}

function blobToFile(blob, originalName, target) {
    const ext = getOutputExt(target, blob.type, originalName);
    const name = replaceExt(originalName, ext);

    return new File([blob], name, {
        type: blob.type,
        lastModified: Date.now(),
    });
}

async function getSvgDimension(src) {
    return new Promise((resolve) => {
        const img = new Image();
        img.src = src;

        img.onload = () => {
            resolve({
                width: img.width,
                height: img.height,
            });
        };

        img.onerror = () => {
            resolve({
                width: 0,
                height: 0,
            });
        };
    });
}

async function convertSvgToTarget(compress, width, height, option) {
    const target = option.format.target.toLowerCase();

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;

    const context = canvas.getContext('2d');

    if (['jpg', 'jpeg'].includes(target)) {
        context.fillStyle = option.format.transparentFill;
        context.fillRect(0, 0, width, height);
    }

    const svg = await new Promise((resolve, reject) => {
        const img = new Image();
        img.src = compress.src;

        img.onload = () => resolve(img);
        img.onerror = reject;
    });

    context.drawImage(svg, 0, 0, width, height);

    let blob;

    if (target === 'avif') {
        blob = await AvifImage.encode(
            context,
            width,
            height,
            option.avif.quality,
            option.avif.speed
        );
    } else {
        blob = await new Promise((resolve) => {
            canvas.toBlob(
                (result) => resolve(result),
                Mimes[target],
                1
            );
        });
    }

    return {
        width,
        height,
        blob,
        src: URL.createObjectURL(blob),
    };
}

export async function compressImageByShotEasy(file, option = {}) {
    if (!file || !file.type?.startsWith('image/')) {
        return file;
    }

    const finalOption = mergeOption(option);
    const src = URL.createObjectURL(file);

    const info = {
        key: createKey(),
        name: file.name || 'image',
        blob: file,
        width: 0,
        height: 0,
    };

    if (file.type === Mimes.svg) {
        const dimension = await getSvgDimension(src);
        info.width = dimension.width;
        info.height = dimension.height;
    }

    return new Promise((resolve, reject) => {
        taskMap.set(info.key, {
            file,
            option: finalOption,
            resolve: (resultFile) => {
                URL.revokeObjectURL(src);
                resolve(resultFile);
            },
            reject: (error) => {
                URL.revokeObjectURL(src);
                reject(error);
            },
        });

        getWorker().postMessage({
            info,
            option: finalOption,
        });
    });
}