#if defined(__ARM_NEON__) || defined(__ARM64_NEON__)

#include "vdrawhelper.h"
#include <arm_neon.h>
#include <string.h>

void memfill32(uint32_t *dest, uint32_t value, int length)
{
    int i = 0;
#if defined(__ARM_NEON__)
    uint32x4_t val4 = vdupq_n_u32(value);
    for (; i + 4 <= length; i += 4)
        vst1q_u32(dest + i, val4);
#endif
    for (; i < length; i++)
        dest[i] = value;
}

void comp_func_solid_SourceOver_neon(uint32_t *dest, int length, uint32_t color,
                                     uint32_t const_alpha)
{
    if (const_alpha != 255) color = BYTE_MUL(color, const_alpha);
    uint32_t ialpha = 255 - (color >> 24);
    for (int i = 0; i < length; i++)
        dest[i] = color + BYTE_MUL(dest[i], ialpha);
}
#endif
