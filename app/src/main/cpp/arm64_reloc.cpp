#include "arm64_reloc.h"

static inline int64_t sext(uint64_t v, int bits) {
    uint64_t m = 1ULL << (bits - 1);
    return (int64_t)((v ^ m) - m);
}

InsnInfo ClassifyInsn(uint32_t insn) {
    if ((insn & 0xFC000000u) == 0x14000000u) return {InsnKind::B, insn};
    if ((insn & 0xFC000000u) == 0x94000000u) return {InsnKind::BL, insn};
    if ((insn & 0xFF000010u) == 0x54000000u) return {InsnKind::B_COND, insn};
    if ((insn & 0x7E000000u) == 0x34000000u) return {InsnKind::CBZ_CBNZ, insn};
    if ((insn & 0x7E000000u) == 0x36000000u) return {InsnKind::TBZ_TBNZ, insn};
    if ((insn & 0x9F000000u) == 0x10000000u) return {InsnKind::ADR, insn};
    if ((insn & 0x9F000000u) == 0x90000000u) return {InsnKind::ADRP, insn};
    uint32_t m = insn & 0x3B000000u;
    if (m == 0x18000000u || m == 0x58000000u || m == 0x98000000u)
        return {InsnKind::LDR_LITERAL, insn};
    return {InsnKind::NORMAL, insn};
}

bool EncodeBranch(uint32_t* out, uint64_t from, uint64_t to, bool link) {
    int64_t off = (int64_t)(to - from);
    if ((off & 3) != 0) return false;
    int64_t imm = off >> 2;
    if (imm < -(1LL << 25) || imm >= (1LL << 25)) return false;
    *out = (link ? 0x94000000u : 0x14000000u) | (uint32_t)(imm & 0x03FFFFFFu);
    return true;
}

static bool enc_adrp(uint32_t* out, uint64_t from, uint64_t page, int rd) {
    int64_t d = (int64_t)(page - (from & ~0xFFFULL));
    if ((d & 0xFFF) != 0) return false;
    int64_t imm = d >> 12;
    if (imm < -(1LL << 20) || imm >= (1LL << 20)) return false;
    *out = 0x90000000u
           | ((uint32_t)(imm & 3) << 29)
           | ((uint32_t)((imm >> 2) & 0x7FFFF) << 5)
           | (uint32_t)rd;
    return true;
}

static bool enc_adr(uint32_t* out, uint64_t from, uint64_t to, int rd) {
    int64_t off = (int64_t)(to - from);
    if (off < -(1LL << 20) || off >= (1LL << 20)) return false;
    *out = 0x10000000u
           | ((uint32_t)(off & 3) << 29)
           | ((uint32_t)((off >> 2) & 0x7FFFF) << 5)
           | (uint32_t)rd;
    return true;
}

static bool enc_adrp_add(uint32_t* out, uint64_t from, uint64_t addr, int rd) {
    if (!enc_adrp(&out[0], from, addr & ~0xFFFULL, rd)) return false;
    out[1] = 0x91000000u
             | ((uint32_t)(addr & 0xFFF) << 10)
             | ((uint32_t)rd << 5)
             | (uint32_t)rd;
    return true;
}

// B.cond skip helper. Emits an inverted-condition B.cond that skips `skip_bytes`
// when the INVERTED condition is true. Caller pairs this with a following B
// (or BL) insn.
static bool enc_bcond_skip(uint32_t* out, uint32_t cond_inv, int64_t skip_bytes) {
    if ((skip_bytes & 3) != 0) return false;
    int64_t imm19 = skip_bytes >> 2;
    if (imm19 < -(1LL << 18) || imm19 >= (1LL << 18)) return false;
    *out = 0x54000000u
           | (((uint32_t)imm19 & 0x7FFFFu) << 5)
           | (cond_inv & 0xFu);
    return true;
}

// CBZ/CBNZ inversion. Preserves sf bit, opcode bits 30:24 (bit 24 toggled),
// rt bits 4:0. Replaces imm19 (bits 23:5) with skip offset.
static uint32_t make_cbz_inv(uint32_t insn, int64_t skip_bytes) {
    uint32_t base = insn & 0xFF00001Fu;                    // sf | opcode | rt
    uint32_t imm19 = (uint32_t)((skip_bytes >> 2) & 0x7FFFFu);
    return (base ^ 0x01000000u) | (imm19 << 5);
}

// TBZ/TBNZ inversion. Preserves b5 (bit 31), opcode bits 30:24 (bit 24 toggled),
// b40 bits 23:19, rt bits 4:0. Replaces imm14 (bits 18:5) with skip offset.
static uint32_t make_tbz_inv(uint32_t insn, int64_t skip_bytes) {
    uint32_t base = insn & 0xFFF8001Fu;                    // b5 | opcode | b40 | rt
    uint32_t imm14 = (uint32_t)((skip_bytes >> 2) & 0x3FFFu);
    return (base ^ 0x01000000u) | (imm14 << 5);
}

// LDR literal -> (stp x16,x17 / adrp x16 / ldr rt / ldp x16,x17).
// Saves x16+x17 around the pair because the ORIGINAL LDR-literal does not
// clobber them, so post-instruction code may still be using them.
// Rejects when rt is x16 or x17 (would clobber our scratch).
static bool enc_ldr_literal(uint32_t* out, uint32_t insn,
                            uint64_t orig_pc, uint64_t new_pc) {
    int rt = (int)(insn & 0x1Fu);
    if (rt == 16 || rt == 17) return false;

    uint32_t imm19_raw = (insn >> 5) & 0x7FFFFu;
    int64_t offset = sext(imm19_raw, 19) << 2;
    uint64_t target = orig_pc + (uint64_t)offset;
    uint32_t off_in_page = (uint32_t)(target & 0xFFFu);

    // ADRP at tramp+4 (after the stp), so its PC is new_pc + 4.
    uint32_t adrp_x16;
    if (!enc_adrp(&adrp_x16, new_pc + 4, target & ~0xFFFULL, 16)) return false;

    uint32_t op_bits = insn & 0xFF000000u;
    uint32_t ldr_insn;
    if (op_bits == 0x18000000u) {                          // LDR Wt
        if (off_in_page & 3) return false;
        uint32_t imm12 = off_in_page >> 2;
        if (imm12 >= 4096) return false;
        ldr_insn = 0xB9400000u | (imm12 << 10) | (16u << 5) | (uint32_t)rt;
    } else if (op_bits == 0x58000000u) {                   // LDR Xt
        if (off_in_page & 7) return false;
        uint32_t imm12 = off_in_page >> 3;
        if (imm12 >= 4096) return false;
        ldr_insn = 0xF9400000u | (imm12 << 10) | (16u << 5) | (uint32_t)rt;
    } else if (op_bits == 0x98000000u) {                   // LDRSW Xt
        if (off_in_page & 3) return false;
        uint32_t imm12 = off_in_page >> 2;
        if (imm12 >= 4096) return false;
        ldr_insn = 0xB9800000u | (imm12 << 10) | (16u << 5) | (uint32_t)rt;
    } else {
        return false;                                      // PRFM literal etc. — reject
    }

    out[0] = 0xA9BF47F0u;   // stp x16, x17, [sp, #-16]!
    out[1] = adrp_x16;
    out[2] = ldr_insn;
    out[3] = 0xA8C147F0u;   // ldp x16, x17, [sp], #16
    return true;
}

int EmitRelocated(uint32_t insn, uint64_t orig_pc, uint64_t new_pc,
                  uint32_t* out, size_t cap) {
    InsnInfo info = ClassifyInsn(insn);
    switch (info.kind) {
        case InsnKind::NORMAL:
            if (cap < 1) return 0;
            out[0] = insn;
            return 1;

        case InsnKind::B: {
            if (cap < 1) return 0;
            int64_t off = sext(insn & 0x03FFFFFFu, 26) << 2;
            uint64_t dest = orig_pc + (uint64_t)off;
            return EncodeBranch(&out[0], new_pc, dest, false) ? 1 : 0;
        }

        case InsnKind::BL: {
            if (cap < 3) return 0;
            int64_t off = sext(insn & 0x03FFFFFFu, 26) << 2;
            uint64_t dest = orig_pc + (uint64_t)off;
            uint32_t bl_insn;
            if (!EncodeBranch(&bl_insn, new_pc + 4, dest, true)) return 0;
            out[0] = 0xA9BF7BFDu;   // stp x29, x30, [sp, #-16]!
            out[1] = bl_insn;
            out[2] = 0xA8C17BFDu;   // ldp x29, x30, [sp], #16
            return 3;
        }

        case InsnKind::ADRP: {
            if (cap < 1) return 0;
            uint32_t lo = (insn >> 29) & 3;
            uint32_t hi = (insn >> 5) & 0x7FFFFu;
            int64_t imm = sext(((int64_t)hi << 2) | lo, 21);
            uint64_t page = (orig_pc & ~0xFFFULL) + (uint64_t)(imm << 12);
            return enc_adrp(&out[0], new_pc, page, (int)(insn & 0x1F)) ? 1 : 0;
        }

        case InsnKind::ADR: {
            if (cap < 2) return 0;
            uint32_t lo = (insn >> 29) & 3;
            uint32_t hi = (insn >> 5) & 0x7FFFFu;
            int64_t imm = sext(((int64_t)hi << 2) | lo, 21);
            uint64_t target = orig_pc + (uint64_t)imm;
            int rd = (int)(insn & 0x1F);
            if (enc_adr(&out[0], new_pc, target, rd)) return 1;
            if (enc_adrp_add(out, new_pc, target, rd)) return 2;
            return 0;
        }

        case InsnKind::B_COND: {
            if (cap < 2) return 0;
            uint32_t imm19_raw = (insn >> 5) & 0x7FFFFu;
            int64_t off = sext(imm19_raw, 19) << 2;
            uint64_t target = orig_pc + (uint64_t)off;
            uint32_t cond = insn & 0xFu;
            if (!enc_bcond_skip(&out[0], cond ^ 1u, 8)) return 0;
            if (!EncodeBranch(&out[1], new_pc + 4, target, false)) return 0;
            return 2;
        }

        case InsnKind::CBZ_CBNZ: {
            if (cap < 2) return 0;
            uint32_t imm19_raw = (insn >> 5) & 0x7FFFFu;
            int64_t off = sext(imm19_raw, 19) << 2;
            uint64_t target = orig_pc + (uint64_t)off;
            out[0] = make_cbz_inv(insn, 8);
            if (!EncodeBranch(&out[1], new_pc + 4, target, false)) return 0;
            return 2;
        }

        case InsnKind::TBZ_TBNZ: {
            if (cap < 2) return 0;
            uint32_t imm14_raw = (insn >> 5) & 0x3FFFu;
            int64_t off = sext(imm14_raw, 14) << 2;
            uint64_t target = orig_pc + (uint64_t)off;
            out[0] = make_tbz_inv(insn, 8);
            if (!EncodeBranch(&out[1], new_pc + 4, target, false)) return 0;
            return 2;
        }

        case InsnKind::LDR_LITERAL: {
            if (cap < 4) return 0;
            if (!enc_ldr_literal(out, insn, orig_pc, new_pc)) return 0;
            return 4;
        }
    }
    return 0;
}