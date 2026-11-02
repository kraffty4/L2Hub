"""Рисунки живых эмблем кланов охотников (docs/PHANTOMS.md, раздел 4.61): теги, значки, сигилы, шары и огонь. Всё нарисовано здесь, ничего чужого.

Детерминировано: кости на SHA-256, из плавающей арифметики только +, -, *, / и sqrt, они одинаковы на любой машине; sin, cos и atan из libm не зовём.
Рисунок — строки RGB размером w×h, в DXT1 их кодирует tools/clan_crests.py.
"""
import hashlib
from math import sqrt

RAMPS = {
    "fire": ((90, 10, 0), (220, 60, 0), (255, 170, 0), (255, 240, 120)),
    "blood": ((60, 0, 0), (160, 0, 10), (235, 25, 25), (255, 130, 110)),
    "gold": ((95, 58, 0), (195, 135, 10), (250, 210, 50), (255, 250, 180)),
    "toxic": ((0, 70, 0), (20, 165, 10), (70, 250, 30), (205, 255, 125)),
    "ice": ((0, 45, 120), (20, 115, 225), (95, 205, 255), (232, 250, 255)),
    "steel": ((60, 65, 75), (125, 130, 145), (195, 200, 210), (252, 252, 255)),
    "violet": ((50, 0, 105), (115, 35, 205), (185, 95, 255), (242, 205, 255)),
    "pink": ((100, 0, 55), (215, 35, 135), (255, 115, 195), (255, 215, 238)),
    "bone": ((100, 88, 66), (172, 158, 128), (228, 218, 194), (255, 251, 238)),
    "teal": ((0, 70, 70), (0, 155, 145), (45, 232, 205), (195, 255, 242)),
    "brown": ((70, 36, 12), (135, 84, 32), (195, 135, 62), (242, 205, 135)),
    "white": ((120, 120, 130), (175, 175, 185), (228, 228, 233), (255, 255, 255)),
    "navy": ((10, 10, 80), (15, 30, 150), (40, 75, 230), (130, 170, 255)),
    "lemon": ((120, 110, 0), (205, 195, 0), (255, 250, 0), (255, 255, 175)),
}
ACCENTS = {
    "gold": (250, 205, 50),
    "blood": (235, 25, 25),
    "ice": (95, 205, 255),
    "toxic": (70, 250, 30),
    "white": (250, 250, 250),
    "violet": (185, 95, 255),
    "bone": (228, 218, 194),
    "brown": (150, 96, 40),
}
BLACK = (0, 0, 0)

# Шрифт 5×7: строки слева направо, единица — закрашено.
FONT = {
    "A": "01110 10001 10001 11111 10001 10001 10001",
    "B": "11110 10001 10001 11110 10001 10001 11110",
    "C": "01110 10001 10000 10000 10000 10001 01110",
    "D": "11110 10001 10001 10001 10001 10001 11110",
    "E": "11111 10000 10000 11110 10000 10000 11111",
    "F": "11111 10000 10000 11110 10000 10000 10000",
    "G": "01110 10001 10000 10111 10001 10001 01111",
    "H": "10001 10001 10001 11111 10001 10001 10001",
    "I": "01110 00100 00100 00100 00100 00100 01110",
    "J": "00111 00010 00010 00010 00010 10010 01100",
    "K": "10001 10010 10100 11000 10100 10010 10001",
    "L": "10000 10000 10000 10000 10000 10000 11111",
    "M": "10001 11011 10101 10101 10001 10001 10001",
    "N": "10001 11001 10101 10011 10001 10001 10001",
    "O": "01110 10001 10001 10001 10001 10001 01110",
    "P": "11110 10001 10001 11110 10000 10000 10000",
    "Q": "01110 10001 10001 10001 10101 10010 01101",
    "R": "11110 10001 10001 11110 10100 10010 10001",
    "S": "01111 10000 10000 01110 00001 00001 11110",
    "T": "11111 00100 00100 00100 00100 00100 00100",
    "U": "10001 10001 10001 10001 10001 10001 01110",
    "V": "10001 10001 10001 10001 10001 01010 00100",
    "W": "10001 10001 10001 10101 10101 11011 10001",
    "X": "10001 10001 01010 00100 01010 10001 10001",
    "Y": "10001 10001 01010 00100 00100 00100 00100",
    "0": "01110 10001 10011 10101 11001 10001 01110",
    "1": "00100 01100 00100 00100 00100 00100 01110",
    "2": "01110 10001 00001 00010 00100 01000 11111",
    "3": "11111 00010 00100 00010 00001 10001 01110",
    "4": "00010 00110 01010 10010 11111 00010 00010",
    "5": "11111 10000 11110 00001 00001 10001 01110",
    "6": "00110 01000 10000 11110 10001 10001 01110",
    "7": "11111 00001 00010 00100 01000 01000 01000",
    "8": "01110 10001 10001 01110 10001 10001 01110",
    "9": "01110 10001 10001 01111 00001 00010 01100",
    "Я": "01111 10001 10001 01111 00101 01001 10001",
    "Ж": "10101 10101 10101 01110 10101 10101 10101",
    "Ф": "00100 01110 10101 10101 10101 01110 00100",
    "Д": "00110 01010 01010 01010 01010 11111 10001",
    "Л": "00111 01001 01001 01001 01001 01001 10001",
    "П": "11111 10001 10001 10001 10001 10001 10001",
    "Ш": "10101 10101 10101 10101 10101 10101 11111",
    "Ю": "10010 10101 10101 11101 10101 10101 10010",
    "Г": "11111 10000 10000 10000 10000 10000 10000",
    "Б": "11111 10000 10000 11110 10001 10001 11110",
    "Ч": "10001 10001 10001 01111 00001 00001 00001",
    "Ω": "01110 10001 10001 10001 01010 01010 11011",
    "Σ": "11111 01000 00100 00010 00100 01000 11111",
    "Ψ": "10101 10101 10101 01110 00100 00100 00100",
    "Δ": "00100 00100 01010 01010 10001 10001 11111",
    "λ": "01000 00100 00100 01010 01010 10001 10001",
}

SINGLES = "ABDEFGHKLMNPRSTWXYЯЖФДШЮБΩΣΨΔλ"
# Частые вертикальные штрихи: жирными при 16×12 слипаются в кирпич.
NEVER_BOLD = "HMNWЖШЮФДΨΣ"
# Двух- и трёхбуквенные — как настоящие клановые теги; знаки с политическим смыслом сюда не берём.
TAGS = (
    "SK", "NR", "KL", "DK", "GG", "PK", "LA", "XX", "TK", "MK", "AK", "BK", "NG", "WR", "VS", "KO", "ЯД", "ЖБ", "TH", "OG", "HF", "RB", "EX", "MX", "AY", "KR", "FL", "NO", "IX",
    "TOP", "WAR", "RIP", "666", "777", "KOT", "OFF", "GOD", "DIE", "PVP", "BAD", "ICE", "SUN", "FOX", "OWL", "DEV", "VIP", "LOL", "OMG", "ACE", "ORC", "ELF", "BOT", "AFK", "БОГ", "ЛЕС", "ЯРЫ", "ДНО", "ПУК", "КОТ", "ROX", "NUB", "MOB", "KIN",
)

# Значки: . пусто, 1–4 тени лесенки от тёмной к светлой, 5 акцент, 0 чёрный. Лесенки — какие подходят значку первыми.
SPRITES = (
    ("skull", ("bone", "white", "toxic", "steel", "blood"), "gold", (
        "....34443....",
        "..344444443..",
        ".34444444443.",
        ".34400400443.",
        ".34000400043.",
        ".34400400443.",
        "..344404443..",
        "...3440443...",
        "...3424243...",
        "...2424242...",
        "....21212....",
    )),
    ("swords", ("steel", "ice", "gold", "blood", "white"), "gold", (
        "4.............4",
        "34...........43",
        ".34.........43.",
        "..34.......43..",
        "...34.....43...",
        "....34...43....",
        ".....34.43.....",
        "......343......",
        "..5..43.34..5..",
        "...5543...3455.",
        "..2.55....55.2.",
        ".2...........2.",
    )),
    ("sword", ("steel", "ice", "fire", "violet"), "gold", (
        "....44....",
        "....43....",
        "....43....",
        "....43....",
        "....43....",
        "....43....",
        "....43....",
        ".5..43..5.",
        ".55555555.",
        "....22....",
        "....22....",
        "....55....",
    )),
    ("axe", ("steel", "blood", "ice", "gold"), "brown", (
        "..33...5...33.",
        ".3443..5..3443",
        "344443.5.34444",
        "4444444544444.",
        "44444435344444",
        "344443.5.34443",
        ".3443..5..3443",
        "..33...5...33.",
        "......55......",
        "......55......",
        "......55......",
        "......55......",
    )),
    ("clover", ("toxic", "gold", "teal"), "brown", (
        "....33.33....",
        "...3443443...",
        "...3444443...",
        ".33.34443.33.",
        "3443.343.3443",
        "3444434344443",
        "3444434344443",
        "3443.343.3443",
        ".33.34443.33.",
        "...34.5.43...",
        "......5......",
        ".....55......",
    )),
    ("moon", ("lemon", "ice", "gold", "white", "toxic"), "white", (
        "....34443.",
        "..3444....",
        ".3443.....",
        ".344......",
        "3444......",
        "3444......",
        "3444......",
        "34444.....",
        ".34444...3",
        ".344444443",
        "..3444443.",
        "....3333..",
    )),
    ("star", ("gold", "blood", "ice", "white", "lemon"), "white", (
        "......4......",
        ".....444.....",
        ".....434.....",
        "....44344....",
        "4444433344444",
        ".34443334443.",
        "..344333443..",
        "...4433344...",
        "..444434444..",
        "..443...344..",
        ".443.....344.",
        ".3.........3.",
    )),
    ("flame", ("fire", "ice", "toxic", "violet"), "white", (
        "....1.......",
        "....21...1..",
        "...121...21.",
        "...2321.121.",
        "..123321232.",
        ".12333323321",
        ".12334333321",
        "123344433321",
        "123344443321",
        "123344443321",
        ".1234444321.",
        "..12333321..",
    )),
    ("bolt", ("lemon", "ice", "violet", "fire"), "white", (
        "....3444",
        "...3444.",
        "..3444..",
        ".34444..",
        "3444444.",
        "...3444.",
        "..3444..",
        "..344...",
        ".344....",
        ".34.....",
        "34......",
        "3.......",
    )),
    ("heart", ("blood", "pink", "violet"), "white", (
        "..222...222..",
        ".23342.23342.",
        "2334432333332",
        "2343333333332",
        "2333333333332",
        ".23333333332.",
        "..233333332..",
        "...2333332...",
        "....23332....",
        ".....232.....",
        "......2......",
    )),
    ("eye", ("white", "bone"), "blood", (
        ".....33333.....",
        "...334444433...",
        ".3344455544433.",
        "344445000544443",
        "344445000544443",
        ".3344455544433.",
        "...334444433...",
        ".....33333.....",
    )),
    ("wings", ("white", "ice", "gold", "blood", "violet"), "gold", (
        "3..............3",
        "43............34",
        "443..........344",
        "4443........3444",
        "44443..55..34444",
        "344443.55.344443",
        ".34444355344443.",
        "..344435534443..",
        "...3443553443...",
        "....34.55.43....",
        ".......55.......",
    )),
    ("bat", ("violet", "blood", "steel", "navy"), "lemon", (
        "......3..3......",
        "3.....3443.....3",
        "33....3553....33",
        "333..344443..333",
        "3333344444433333",
        "3333344444433333",
        ".33.3.3443.3.33.",
        "......3..3......",
    )),
    ("wolf", ("steel", "white", "brown", "ice"), "blood", (
        "3...........3",
        "33.........33",
        "343.......343",
        "3443.....3443",
        "3444444444443",
        "3445544445543",
        "3444444444443",
        ".34444444443.",
        "..344404443..",
        "...3400043...",
        "....34443....",
        ".....333.....",
    )),
    ("cat", ("gold", "white", "steel", "pink", "fire"), "toxic", (
        ".3.........3.",
        ".43.......34.",
        ".44333333344.",
        ".44444444444.",
        ".44554445544.",
        ".44504445044.",
        ".44444444444.",
        "2.444404444.2",
        ".23440004432.",
        "...3444443...",
    )),
    ("spider", ("steel", "blood", "violet", "toxic"), "blood", (
        "3...3...3...3",
        ".3...3.3...3.",
        "..3...3...3..",
        "...3.333.3...",
        "3...34443...3",
        ".33344544333.",
        "...3454543...",
        ".33344454333.",
        "3..3444443..3",
        "..3.34443.3..",
        ".3...333...3.",
    )),
    ("mushroom", ("blood", "violet", "fire", "teal"), "bone", (
        "....33333....",
        "..334445433..",
        ".33544444553.",
        "3344444444433",
        "3445544444553",
        "3333333333333",
        "....55555....",
        "....55555....",
        "....55555....",
        "...5555555...",
    )),
    ("pine", ("toxic", "teal", "ice", "steel"), "brown", (
        ".....4.....",
        "....343....",
        "...34443...",
        "..3444443..",
        "....344....",
        "...34443...",
        "..3444443..",
        ".344444443.",
        "...34443...",
        ".344444443.",
        "34444444443",
        "....555....",
    )),
    ("anchor", ("ice", "steel", "gold", "navy"), "white", (
        ".....33.....",
        "....3..3....",
        ".....33.....",
        "..33333333..",
        ".....33.....",
        "3....33....3",
        "33...33...33",
        "343..33..343",
        ".33..33..33.",
        "..33333333..",
        "...333333...",
        ".....33.....",
    )),
    ("trident", ("gold", "ice", "blood", "teal"), "white", (
        "4....4....4",
        "44...4...44",
        "34...4...43",
        "34...4...43",
        "34...4...43",
        ".34..4..43.",
        "..3344433..",
        ".....4.....",
        ".....3.....",
        ".....3.....",
        ".....3.....",
        ".....3.....",
    )),
    ("hammer", ("steel", "gold", "ice", "blood"), "brown", (
        ".44444444444.",
        "3444444444443",
        "3333344433333",
        "2222233322222",
        ".....255.....",
        ".....255.....",
        ".....255.....",
        ".....255.....",
        ".....255.....",
        ".....255.....",
        ".....355.....",
        ".....355.....",
    )),
    ("pistol", ("steel", "gold", "white"), "brown", (
        "...........3..",
        "44444444444444",
        "34444444444444",
        "33333333333333",
        ".....3.4555555",
        "......34.5555",
        "...........555",
        "...........555",
        "............55",
    )),
    ("bomb", ("steel", "navy", "violet"), "fire", (
        ".......5.5.",
        "........5..",
        "......33...",
        "....33333..",
        "..33333333.",
        ".3344333333",
        ".3443333322",
        ".3433333322",
        ".3333333222",
        "..33333222.",
        "...322222..",
    )),
    ("flask", ("toxic", "violet", "blood", "ice"), "white", (
        "...555...",
        "....5....",
        "...5.5...",
        "...5.5...",
        "..5...5..",
        ".5.....5.",
        "5.33333.5",
        "534444335",
        "534444335",
        "5.34443.5",
        ".5.333.5.",
        "..55555..",
    )),
    ("key", ("gold", "steel", "brown"), "white", (
        ".3333.........",
        "344443........",
        "44..4444444444",
        "44..4433333333",
        "344443...3.3.3",
        ".3333....3.3.3",
    )),
    ("gem", ("ice", "blood", "toxic", "violet", "pink"), "white", (
        "...33333333...",
        "..3455444443..",
        ".344544444443.",
        "33333333333333",
        ".34444444443.",
        "..343333343..",
        "...34333443...",
        "....344443....",
        ".....3443.....",
        "......33......",
    )),
    ("sun", ("gold", "fire", "lemon"), "white", (
        "......4......",
        "..4...4...4..",
        "...4.....4...",
        ".....333.....",
        "....34443....",
        "44.3444443.44",
        "....34443....",
        ".....333.....",
        "...4.....4...",
        "..4...4...4..",
        "......4......",
    )),
    ("snow", ("ice", "white", "teal"), "white", (
        "......4......",
        "...4.343.4...",
        "....4.4.4....",
        ".4...444...4.",
        "..4...4...4..",
        "...44444444...",
        "..4...4...4..",
        ".4...444...4.",
        "....4.4.4....",
        "...4.343.4...",
        "......4......",
    )),
    ("ankh", ("gold", "ice", "toxic", "blood"), "white", (
        "...333...",
        "..34443..",
        ".34...43.",
        ".34...43.",
        "..34.43..",
        "...444...",
        "344444443",
        "333444333",
        "...444...",
        "...444...",
        "...444...",
        "...333...",
    )),
    ("cross", ("blood", "white", "gold", "ice"), "white", (
        "...343...",
        "...343...",
        "...343...",
        "344444443",
        "344444443",
        "...343...",
        "...343...",
        "...343...",
        "...343...",
        "...343...",
        "...343...",
        "...232...",
    )),
    ("horns", ("blood", "fire", "violet", "steel"), "lemon", (
        "4...........4",
        "34.........43",
        "344.......443",
        "3344.....4433",
        ".334444444433.",
        "..333333333..",
        "..355333553..",
        "..333333333..",
        "...3003003...",
        "....33333....",
    )),
    ("ghost", ("white", "ice", "toxic", "violet"), "blood", (
        "...33333...",
        "..3444443..",
        ".344444443.",
        ".340444043.",
        ".340444043.",
        ".344444443.",
        ".344400443.",
        ".344400443.",
        ".344444443.",
        ".344444443.",
        ".34.344.43.",
        ".3...3...3.",
    )),
    ("paw", ("brown", "toxic", "pink", "steel", "gold"), "white", (
        "....33.33....",
        "...344.443...",
        "...344.443...",
        ".33..3.3..33.",
        "344.......443",
        "344..333..443",
        ".33.34443.33.",
        "...3444443...",
        "..344444443..",
        "..344444443..",
        "...3443443...",
    )),
    ("bones", ("bone", "white", "toxic"), "white", (
        "33.........33",
        "443.......344",
        ".343.....343.",
        "...343.343...",
        ".....343.....",
        "....34343....",
        "...343.343...",
        ".343.....343.",
        "443.......344",
        "33.........33",
    )),
    ("scythe", ("steel", "violet", "blood", "ice"), "brown", (
        "...34444444..",
        ".34444444445.",
        "3444.....5...",
        "44.......5...",
        "3........5...",
        ".........5...",
        "........5....",
        "........5....",
        "........5....",
        ".......5.....",
        ".......5.....",
        ".......5.....",
    )),
    ("bow", ("brown", "gold", "toxic", "steel"), "white", (
        "..33.........",
        ".34.5........",
        ".34..5.......",
        "34...5....4..",
        "34...5.....4.",
        "3333333333334",
        "34...5.....4.",
        "34...5....4..",
        ".34..5.......",
        ".34.5........",
        "..33.........",
    )),
    ("hourglass", ("gold", "brown", "ice"), "bone", (
        "333333333",
        "333333333",
        ".5.....5.",
        ".5.555.5.",
        "..55555..",
        "...555...",
        "...5.5...",
        "..5...5..",
        ".5..5..5.",
        ".5.555.5.",
        "333333333",
        "333333333",
    )),
    ("pumpkin", ("fire", "gold"), "toxic", (
        "......55.....",
        ".....55......",
        "..33433433...",
        ".34443434443.",
        "344044340443",
        "340043434003",
        "344443034443",
        "340000000043",
        ".34040404043.",
        "..334434433..",
    )),
    ("raven", ("steel", "navy", "violet"), "lemon", (
        "...33........",
        "..3543.......",
        "111344......",
        "...3444.....",
        "...344443...",
        "..34444443..",
        "..344444443.",
        "...344444443",
        "....34444.33",
        ".....3.3...3",
        ".....3.3....",
        "....33.33...",
    )),
    ("tower", ("steel", "brown", "bone"), "fire", (
        "3.3.3.3.3",
        "333333333",
        "344444443",
        ".3444443.",
        ".3445443.",
        ".3445443.",
        ".3444443.",
        ".3444443.",
        ".3440443.",
        "344000443",
        "344000443",
        "333333333",
    )),
    ("crown", ("gold", "lemon", "steel", "ice"), "blood", (
        "5.....5.....5",
        "3.....4.....3",
        "34...343...43",
        "344.34443.443",
        "3443444443443",
        "3444444444443",
        "3445445445443",
        "3333333333333",
        "2222222222222",
    )),
    ("spade", ("steel", "white", "violet", "blood"), "white", (
        ".....4.....",
        "....444....",
        "...34443...",
        "..3444443..",
        ".344444443.",
        "34444444443",
        "34444444443",
        "3443.3.3443",
        ".33..3..33.",
        ".....3.....",
        "....333....",
        "...33333...",
    )),
    ("rose", ("blood", "pink", "violet"), "toxic", (
        "....33333....",
        "...3443443...",
        "..344334433..",
        "..343443343..",
        "..344334443..",
        "...3444443...",
        "....33333....",
        ".....5.......",
        "..55.5.......",
        "...5555......",
        ".....5.55....",
        ".....555.....",
    )),
    ("drop", ("blood", "toxic", "ice", "violet"), "white", (
        "....3....",
        "....3....",
        "...343...",
        "...343...",
        "..34443..",
        ".3444443.",
        ".3544443.",
        ".3544443.",
        ".3454443.",
        "..34443..",
        "...333...",
    )),
    ("smile", ("gold", "lemon", "toxic", "pink"), "white", (
        "....3333....",
        "..33444433..",
        ".3444444443.",
        ".3440440443.",
        "344404404443",
        "344444444443",
        "340444444043",
        "344044440443",
        ".3440000443.",
        ".3444444443.",
        "..33444433..",
        "....3333....",
    )),
    ("butterfly", ("violet", "pink", "ice", "fire"), "white", (
        ".33...5...33.",
        "3443..5..3443",
        "34553.5.35543",
        "3455435345543",
        "3444435344443",
        ".34443534443.",
        "..344353443..",
        ".3443.5.3443.",
        ".344..5..443.",
        "..33..5..33..",
    )),
    ("oak", ("toxic", "teal", "fire"), "brown", (
        "....33333....",
        "..334444433..",
        ".34443444443.",
        "3444444434443",
        "3443444444443",
        "3444444444433",
        ".34444344443.",
        "..33344433...",
        ".....55......",
        ".....55......",
        "....555......",
        "...55555.....",
    )),
    ("shield", ("steel", "gold", "blood", "ice", "white"), "white", (
        "4444444444444",
        "4333333333334",
        "4333333333334",
        "4333355533334",
        "4333355533334",
        "4355555555534",
        "4355555555534",
        ".43333553334.",
        ".43333553334.",
        "..433355334..",
        "...4333334...",
        ".....444.....",
    )),
)


class Dice:
    """Кости на SHA-256: тот же номер — те же броски на любой машине и в любой версии Python."""

    def __init__(self, *seed):
        self._seed = "|".join(str(part) for part in seed)
        self._counter = 0
        self._left = b""

    def _bytes(self, count: int) -> bytes:
        while len(self._left) < count:
            self._left += hashlib.sha256(f"{self._seed}|{self._counter}".encode()).digest()
            self._counter += 1
        out, self._left = self._left[:count], self._left[count:]
        return out

    def below(self, limit: int) -> int:
        return int.from_bytes(self._bytes(4), "little") % limit

    def unit(self) -> float:
        return self.below(65536) / 65536

    def pick(self, items):
        return items[self.below(len(items))]

    def chance(self, percent: int) -> bool:
        return self.below(100) < percent


def clamp(value: float) -> float:
    return 0.0 if value < 0 else 1.0 if value > 1 else value


def blend(a: tuple, b: tuple, t: float) -> tuple:
    return tuple(int(p + (q - p) * t + 0.5) for p, q in zip(a, b))


def ramp_at(ramp: tuple, t: float) -> tuple:
    t = clamp(t) * (len(ramp) - 1)
    low = min(int(t), len(ramp) - 2)
    return blend(ramp[low], ramp[low + 1], t - low)


def noise(seed: int, x: float, y: float) -> float:
    """Плавный шум 0..1: решётка на целочисленном хеше, смешивание многочленом."""

    def lattice(ix: int, iy: int) -> float:
        value = (ix * 73856093 ^ iy * 19349663 ^ seed * 83492791) & 0xFFFFFFFF
        value = (value * 2654435761 & 0xFFFFFFFF) ^ (value >> 13)
        return (value * 2654435761 & 0xFFFFFFFF) / 0xFFFFFFFF

    ix, iy = int(x // 1), int(y // 1)
    fx, fy = x - ix, y - iy
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    top = lattice(ix, iy) * (1 - fx) + lattice(ix + 1, iy) * fx
    bottom = lattice(ix, iy + 1) * (1 - fx) + lattice(ix + 1, iy + 1) * fx
    return top * (1 - fy) + bottom * fy


def fbm(seed: int, x: float, y: float) -> float:
    return noise(seed, x, y) * 0.6 + noise(seed + 1, x * 2, y * 2) * 0.28 + noise(seed + 2, x * 4, y * 4) * 0.12


def angle_of(y: float, x: float) -> float:
    """atan2, поделённый на полный круг: 0..1, многочленом, а не из libm."""
    ax, ay = abs(x), abs(y)
    if ax < 1e-9 and ay < 1e-9:
        return 0.0
    if ax >= ay:
        r = ay / ax
        a = r * (0.125 + (1 - r) * 0.0432)
    else:
        r = ax / ay
        a = 0.25 - r * (0.125 + (1 - r) * 0.0432)
    if x < 0:
        a = 0.5 - a
    if y < 0:
        a = 1 - a
    return a


def blank(w: int, h: int, color: tuple = BLACK) -> list[list[tuple]]:
    return [[color] * w for _ in range(h)]


def mask_new(w: int, h: int) -> list[list[float]]:
    return [[0.0] * w for _ in range(h)]


def paint(canvas: list, mask: list, color) -> None:
    """color — цвет или функция (x, y) → цвет."""
    for y, row in enumerate(mask):
        for x, alpha in enumerate(row):
            if alpha > 0:
                canvas[y][x] = blend(canvas[y][x], color(x, y) if callable(color) else color, alpha)


def grown(mask: list, reach: int = 1) -> list:
    """Маска, раздутая на reach пикселей: для обводки."""
    h, w = len(mask), len(mask[0])
    out = mask_new(w, h)
    for y in range(h):
        for x in range(w):
            best = 0.0
            for dy in range(-reach, reach + 1):
                for dx in range(-reach, reach + 1):
                    if 0 <= y + dy < h and 0 <= x + dx < w:
                        best = max(best, mask[y + dy][x + dx])
            out[y][x] = best
    return out


def blurred(mask: list) -> list:
    h, w = len(mask), len(mask[0])
    out = mask_new(w, h)
    for y in range(h):
        for x in range(w):
            total, weight = 0.0, 0
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    if 0 <= y + dy < h and 0 <= x + dx < w:
                        total += mask[y + dy][x + dx]
                    weight += 1
            out[y][x] = total / weight
    return out


def glyph_into(mask: list, char: str, left: int, top: int, box_w: int, box_h: int, bold: bool = False) -> None:
    """Буква шрифта 5×7 в прямоугольник: край сглаживается перевыборкой, как у уменьшенной картинки."""
    rows = FONT[char].split()
    columns = 6 if bold else 5

    def lit(cx: int, cy: int) -> bool:
        if rows[cy][min(cx, 4)] == "1" and cx < 5:
            return True
        return bold and cx > 0 and rows[cy][cx - 1] == "1"

    for py in range(box_h):
        for px in range(box_w):
            hits = 0
            for sy in range(4):
                for sx in range(4):
                    cx = int((px + (sx + 0.5) / 4) * columns / box_w)
                    cy = int((py + (sy + 0.5) / 4) * 7 / box_h)
                    hits += lit(cx, cy)
            if 0 <= top + py < len(mask) and 0 <= left + px < len(mask[0]):
                mask[top + py][left + px] = max(mask[top + py][left + px], hits / 16)


def text_mask(text: str, w: int, h: int, height: int, bold: bool = False, slant: int = 0) -> list:
    """Строка по центру. Ширина буквы — сколько влезает, но не шире, чем выглядит буквой."""
    mask = mask_new(w, h)
    gap = 1
    width = min((w - gap * (len(text) - 1) - abs(slant)) // len(text), max(4, height * 7 // 10 + (1 if bold else 0)))
    total = width * len(text) + gap * (len(text) - 1)
    left = (w - total) // 2
    top = (h - height) // 2
    for n, char in enumerate(text):
        glyph_into(mask, char, left + n * (width + gap), top, width, height, bold)
    if slant:
        out = mask_new(w, h)
        for y in range(h):
            shift = (slant * (h - 1 - 2 * y)) // (2 * (h - 1)) if h > 1 else 0
            for x in range(w):
                if 0 <= x + shift < w:
                    out[y][x + shift] = mask[y][x]
        return out
    return mask


def ring_mask(w: int, h: int, outer: float, inner: float) -> list:
    mask = mask_new(w, h)
    cx, cy = (w - 1) / 2, (h - 1) / 2
    for y in range(h):
        for x in range(w):
            d = sqrt((x - cx) ** 2 + (y - cy) ** 2)
            mask[y][x] = clamp(outer - d + 0.5) * clamp(d - inner + 0.5)
    return mask


def disc_mask(w: int, h: int, radius: float) -> list:
    mask = mask_new(w, h)
    cx, cy = (w - 1) / 2, (h - 1) / 2
    for y in range(h):
        for x in range(w):
            mask[y][x] = clamp(radius - sqrt((x - cx) ** 2 + (y - cy) ** 2) + 0.5)
    return mask


def shield_mask(w: int, h: int, inset: int) -> list:
    """Щит: прямой верх, снизу сходится к острию. inset — на сколько сжать: внутренность для рамки."""
    mask = mask_new(w, h)
    cx = (w - 1) / 2
    half = cx - 1 - inset
    for y in range(inset, h - inset):
        low = y / (h - 1)
        width = half if low < 0.55 else half * (1 - (low - 0.55) / 0.5)
        for x in range(w):
            mask[y][x] = clamp(width - abs(x - cx) + 0.5)
    return mask


def background(dice: Dice, w: int, h: int, hint: tuple) -> list:
    """Почти всегда чёрный, как у настоящих кланов; реже тёмный оттенок, виньетка или звёзды."""
    kind = dice.below(100)
    if kind < 62:
        return blank(w, h)
    dark = tuple(c // 5 for c in hint)
    canvas = blank(w, h)
    cx, cy = (w - 1) / 2, (h - 1) / 2
    limit = sqrt(cx * cx + cy * cy)
    if kind < 74:  # виньетка: свет от центра
        for y in range(h):
            for x in range(w):
                d = sqrt((x - cx) ** 2 + (y - cy) ** 2) / limit
                canvas[y][x] = blend(tuple(c // 3 for c in hint), BLACK, clamp(d * 1.25))
    elif kind < 86:  # заливка сверху вниз
        for y in range(h):
            canvas[y] = [blend(BLACK, tuple(c // 2 for c in hint), y / (h - 1))] * w
    elif kind < 94:  # дым
        seed = dice.below(100000)
        for y in range(h):
            for x in range(w):
                canvas[y][x] = blend(BLACK, tuple(c // 2 for c in hint), clamp(fbm(seed, x / 5, y / 5) * 1.5 - 0.3))
    else:  # звёзды
        canvas = blank(w, h, dark)
        for _ in range(7):
            canvas[dice.below(h)][dice.below(w)] = (200, 200, 220)
    return canvas


def sprite_rows(name_rows: tuple, flip: bool) -> list[str]:
    rows = [row[::-1] if flip else row for row in name_rows]
    width = max(len(row) for row in rows)
    return [row.ljust(width, ".") for row in rows]


def draw_sprite(canvas: list, rows: list[str], ramp: tuple, accent: tuple, silhouette: tuple = None) -> None:
    h, w = len(canvas), len(canvas[0])
    top, left = (h - len(rows)) // 2, (w - len(rows[0])) // 2
    shades = {"1": 0.1, "2": 0.4, "3": 0.72, "4": 1.0}
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            if cell == "." or not (0 <= top + y < h and 0 <= left + x < w):
                continue
            if silhouette is not None:
                color = BLACK if cell == "0" else silhouette
            elif cell == "0":
                color = BLACK
            elif cell == "5":
                color = accent
            else:
                color = ramp_at(ramp, shades[cell])
            canvas[top + y][left + x] = color


def sprite_mask(rows: list[str], w: int, h: int) -> list:
    mask = mask_new(w, h)
    top, left = (h - len(rows)) // 2, (w - len(rows[0])) // 2
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            if cell != "." and 0 <= top + y < h and 0 <= left + x < w:
                mask[top + y][left + x] = 1.0
    return mask


def crest_icon(dice: Dice, number: int, w: int, h: int) -> list:
    name, ramps, accent, rows = SPRITES[number % len(SPRITES)]
    round_ = number // len(SPRITES)
    ramp_name = ramps[round_ % len(ramps)]
    ramp = RAMPS[ramp_name]
    accent_color = ACCENTS.get(accent, ACCENTS["white"])
    rows = sprite_rows(rows, dice.chance(35))
    plate = dice.below(100)
    if plate < 13:  # яркая плашка и тёмный силуэт
        canvas = blank(w, h)
        for y in range(h):
            canvas[y] = [ramp_at(ramp, 0.55 + 0.45 * (1 - y / (h - 1)))] * w
        draw_sprite(canvas, rows, ramp, accent_color, silhouette=tuple(c // 8 for c in ramp[0]))
        return canvas
    canvas = background(dice, w, h, ramp[1])
    mask = sprite_mask(rows, w, h)
    if dice.chance(35):  # свечение
        halo = blurred(grown(mask))
        paint(canvas, [[a * 0.55 for a in row] for row in halo], ramp[1])
    if dice.chance(25):  # чёрная обводка отделяет от фона
        paint(canvas, grown(mask), BLACK)
    draw_sprite(canvas, rows, ramp, accent_color)
    return canvas


def crest_tag(dice: Dice, number: int, w: int, h: int) -> list:
    singles = len(SINGLES)
    if number < singles:
        text = SINGLES[number]
    else:
        text = TAGS[(number - singles) % len(TAGS)]
    ramp_name = dice.pick(("gold", "ice", "white", "blood", "toxic", "fire", "steel", "violet", "lemon", "teal", "pink"))
    ramp = RAMPS[ramp_name]
    style = dice.below(100)
    if len(text) == 1:
        height = dice.pick((10, 11, 12))
    elif len(text) == 2:
        height = dice.pick((8, 9, 10))
    else:
        height = dice.pick((7, 8, 9))
    bold = len(text) < 3 and dice.chance(60) and not set(text) & set(NEVER_BOLD)
    slant = dice.pick((0, 0, 2, 3)) if len(text) > 1 else dice.pick((0, 0, 2))
    if style < 14:  # плашка: тёмные буквы на яркой заливке
        canvas = blank(w, h)
        for y in range(h):
            canvas[y] = [ramp_at(ramp, 0.5 + 0.5 * (1 - y / (h - 1)))] * w
        paint(canvas, text_mask(text, w, h, height, bold, slant), tuple(c // 6 for c in ramp[0]))
        return canvas
    if style < 22 and len(text) == 1:  # щит с буквой
        canvas = blank(w, h)
        outer, inner = shield_mask(w, h, 0), shield_mask(w, h, 1)
        paint(canvas, outer, lambda x, y: ramp_at(ramp, 0.95 - 0.5 * y / (h - 1)))
        paint(canvas, inner, tuple(c // 4 for c in ramp[0]))
        paint(canvas, text_mask(text, w, h - 2, 7), ramp_at(ramp, 1.0))
        return canvas
    if style < 26 and len(text) < 3:  # рамка, как нарисованная от руки
        canvas = blank(w, h)
        frame = mask_new(w, h)
        for y in range(h):
            for x in range(w):
                frame[y][x] = 1.0 if x in (0, w - 1) or y in (0, h - 1) else 0.0
        paint(canvas, frame, ramp_at(ramp, 0.7))
        inner_text = text_mask(text, w, h, 8, False)
        paint(canvas, inner_text, ramp_at(ramp, 1.0))
        return canvas
    canvas = background(dice, w, h, ramp[1])
    mask = text_mask(text, w, h, height, bold, slant)
    if style < 34 and len(text) == 1:  # кольцо вокруг буквы
        canvas = blank(w, h)
        small = text_mask(text, w, h, 7, False)
        paint(canvas, ring_mask(w, h, 5.9, 4.6), lambda x, y: ramp_at(ramp, 0.4 + 0.6 * (1 - y / (h - 1))))
        paint(canvas, small, ramp_at(ramp, 1.0))
        return canvas
    if style < 44:  # значок за буквами, приглушённый
        name, _, _, rows = SPRITES[dice.below(len(SPRITES))]
        rows = sprite_rows(rows, False)
        back = blank(w, h, (0, 0, 1))
        dim = tuple(tuple(c // 2 for c in stop) for stop in RAMPS[dice.pick(("steel", "brown", "navy", "blood"))])
        draw_sprite(back, rows, dim, tuple(c // 2 for c in ACCENTS["bone"]))
        for y in range(h):
            for x in range(w):
                if back[y][x] != (0, 0, 1):
                    canvas[y][x] = back[y][x]
        paint(canvas, grown(mask), BLACK)
    elif style < 62:  # свечение
        paint(canvas, [[a * 0.8 for a in row] for row in blurred(grown(mask))], ramp[1])
    elif style < 80:  # тень вправо-вниз
        shadow = mask_new(w, h)
        for y in range(h - 1):
            for x in range(w - 1):
                shadow[y + 1][x + 1] = mask[y][x]
        paint(canvas, shadow, tuple(c // 2 for c in ramp[0]))
    top = (h - height) // 2
    chrome = style % 3 == 0

    def shade(x: int, y: int) -> tuple:
        t = (y - top) / max(1, height - 1)
        if chrome:  # хром: светлый верх, тёмная полоса посередине, светлый низ
            return ramp_at(ramp, 1.0 - t) if t < 0.5 else ramp_at(ramp, 0.45 + (t - 0.5) * 1.1)
        return ramp_at(ramp, 1.0 - t * 0.5)

    paint(canvas, mask, shade)
    return canvas


def crest_sigil(dice: Dice, number: int, w: int, h: int) -> list:
    """Симметричный знак из клеточного автомата: каждый свой, читается как руна или герб."""
    half = (w + 1) // 2
    cells = [[dice.chance(46) for _ in range(half)] for _ in range(h)]
    for _ in range(2):
        next_cells = [row[:] for row in cells]
        for y in range(h):
            for x in range(half):
                around = 0
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        if dx or dy:
                            nx = x + dx
                            nx = w - 1 - nx if nx >= half else nx  # отражение через ось
                            if 0 <= y + dy < h and 0 <= nx < half:
                                around += cells[y + dy][nx]
                next_cells[y][x] = around in (2, 3, 4) if cells[y][x] else around in (3, 4)
        cells = next_cells
    mask = mask_new(w, h)
    for y in range(h):
        for x in range(w):
            mx = x if x < half else w - 1 - x
            mask[y][x] = 1.0 if cells[y][mx] else 0.0
    for x in range(w):
        mask[0][x] = 0.0 if x < 2 or x > w - 3 else mask[0][x]
        mask[h - 1][x] = 0.0 if x < 2 or x > w - 3 else mask[h - 1][x]
    ramp = RAMPS[dice.pick(("toxic", "ice", "fire", "violet", "gold", "blood", "teal", "pink", "white"))]
    canvas = blank(w, h)
    if dice.chance(60):
        paint(canvas, [[a * 0.6 for a in row] for row in blurred(mask)], ramp[0])
    paint(canvas, mask, lambda x, y: ramp_at(ramp, 1.0 - 0.7 * abs(y - (h - 1) / 2) / ((h - 1) / 2)))
    return canvas


def crest_orb(dice: Dice, number: int, w: int, h: int) -> list:
    """Шар: планета, око или кристалл, свет сверху-слева."""
    ramp = RAMPS[dice.pick(("ice", "toxic", "fire", "violet", "teal", "gold", "blood", "steel"))]
    second = RAMPS[dice.pick(("toxic", "brown", "white", "navy", "fire"))]
    seed = dice.below(100000)
    radius = 5.6
    cx, cy = (w - 1) / 2, (h - 1) / 2
    canvas = blank(w, h)
    kind = dice.below(3)
    for y in range(h):
        for x in range(w):
            dx, dy = (x - cx) / radius, (y - cy) / radius
            r2 = dx * dx + dy * dy
            d = sqrt(r2)
            edge = clamp((1 - d) * radius + 0.5)
            if edge <= 0:
                continue
            z = sqrt(max(0.0, 1 - r2))
            light = clamp(-0.5 * dx - 0.55 * dy + 0.67 * z)
            if kind == 0:  # планета с материками
                land = fbm(seed, x / 3.4, y / 3.4)
                base = ramp_at(second, land) if land > 0.5 else ramp_at(ramp, 0.35 + land)
            elif kind == 1:  # око: радужка и зрачок
                base = BLACK if d < 0.28 else ramp_at(ramp, 1.2 - d) if d < 0.72 else ramp_at(RAMPS["white"], 0.6)
            else:  # кристалл: чистая заливка
                base = ramp_at(ramp, 0.2 + 0.6 * z)
            spec = light * light
            spec = spec * spec * spec
            color = tuple(int(c * (0.28 + 0.85 * light)) for c in base)
            color = tuple(min(255, int(c + 220 * spec)) for c in color)
            canvas[y][x] = blend(BLACK, color, edge)
    return canvas


def crest_fire(dice: Dice, number: int, w: int, h: int) -> list:
    """Живой огонь, иногда с буквой поверх."""
    ramp_name = dice.pick(("fire", "fire", "ice", "toxic", "violet"))
    ramp = RAMPS[ramp_name]
    seed = dice.below(100000)
    canvas = blank(w, h)
    for y in range(h):
        for x in range(w):
            spread = abs(x - (w - 1) / 2) / ((w - 1) / 2)
            v = fbm(seed, x / 2.6, y / 2.2) + (y / (h - 1)) * 0.95 - 0.55 - spread * spread * 0.5
            canvas[y][x] = blend(BLACK, ramp_at(ramp, v * 1.7), clamp(v * 4))
    if dice.chance(40):
        letter = dice.pick(SINGLES)
        mask = text_mask(letter, w, h, 9, letter not in NEVER_BOLD)
        paint(canvas, grown(mask), BLACK)
        paint(canvas, mask, (250, 250, 250))
    return canvas


def crest_vortex(dice: Dice, number: int, w: int, h: int) -> list:
    """Спираль: портал или водоворот."""
    ramp = RAMPS[dice.pick(("violet", "ice", "toxic", "fire", "steel", "teal", "blood"))]
    arms = dice.pick((1, 2, 3))
    twist = dice.pick((0.9, 1.2, 1.6))
    direction = dice.pick((1, -1))
    cx, cy = (w - 1) / 2, (h - 1) / 2
    canvas = blank(w, h)
    for y in range(h):
        for x in range(w):
            dx, dy = x - cx, y - cy
            d = sqrt(dx * dx + dy * dy) / 6.2
            if d > 1.06:
                continue
            phase = (angle_of(dy, dx) * arms * direction + d * twist * arms) % 1
            wave = 1 - abs(phase * 2 - 1)
            value = clamp(wave * wave * 1.3 + 0.25 * (1 - d))
            canvas[y][x] = blend(BLACK, ramp_at(ramp, value), clamp((1.06 - d) * 6.2))
    return canvas


def crest_storm(dice: Dice, number: int, w: int, h: int) -> list:
    """Молния на грозовом небе."""
    seed = dice.below(100000)
    tone = dice.pick(((20, 30, 90), (60, 20, 90), (20, 70, 80), (70, 20, 30)))
    canvas = blank(w, h)
    for y in range(h):
        for x in range(w):
            canvas[y][x] = blend(BLACK, tone, clamp(fbm(seed, x / 4, y / 3) * 1.8 - 0.35))
    mask = mask_new(w, h)
    x = dice.below(w // 2) + w // 4
    for y in range(h):
        mask[y][max(0, min(w - 1, x))] = 1.0
        x += dice.pick((-2, -1, -1, 0, 1, 1, 2))
        if y == h // 2 and dice.chance(70):
            bx = x
            for by in range(y + 1, h):
                bx += dice.pick((1, 1, 2)) * (1 if bx < w // 2 else -1)
                if 0 <= bx < w:
                    mask[by][bx] = 0.8
    paint(canvas, [[a * 0.7 for a in row] for row in blurred(grown(mask))], (150, 170, 255))
    paint(canvas, mask, (245, 250, 255))
    return canvas


def crest_kaleido(dice: Dice, number: int, w: int, h: int) -> list:
    """Калейдоскоп: четверть рисунка и три её отражения."""
    first, second = RAMPS[dice.pick(("gold", "ice", "blood", "toxic", "violet", "teal"))], RAMPS[dice.pick(("navy", "steel", "fire", "pink", "white"))]
    seed = dice.below(100000)
    canvas = blank(w, h)
    half_w, half_h = (w + 1) // 2, (h + 1) // 2
    scale = dice.pick((2.0, 2.6, 3.2))
    for y in range(half_h):
        for x in range(half_w):
            v = fbm(seed, x / scale, y / scale)
            if v < 0.42:
                color = BLACK
            elif v < 0.56:
                color = ramp_at(first, (v - 0.42) * 7)
            else:
                color = ramp_at(second, (v - 0.56) * 5)
            for yy in (y, h - 1 - y):
                for xx in (x, w - 1 - x):
                    canvas[yy][xx] = color
    return canvas


def crest_bars(dice: Dice, number: int, w: int, h: int) -> list:
    """Закат в полосах: яркая заливка с разделителем и знаком."""
    ramp = RAMPS[dice.pick(("fire", "gold", "blood", "violet", "ice", "pink", "toxic"))]
    canvas = blank(w, h)
    for y in range(h):
        for x in range(w):
            canvas[y][x] = ramp_at(ramp, y / (h - 1) * 0.9 + x / (w - 1) * 0.1)
    band = dice.pick((h // 2, h // 2 + 2, h // 2 + 3))
    for y in range(band, min(h, band + dice.pick((1, 2)))):
        canvas[y] = [(250, 250, 250)] * w
    for y in range(min(h, band + 2), h):
        canvas[y] = [BLACK] * w
    text = dice.pick(TAGS)[:2]
    paint(canvas, text_mask(text, w, band + 1, min(band, 7), not set(text) & set(NEVER_BOLD)), tuple(c // 6 for c in ramp[0]))
    return canvas


FAMILIES = (
    (crest_icon, 112),
    (crest_tag, 80),
    (crest_sigil, 20),
    (crest_orb, 12),
    (crest_fire, 10),
    (crest_vortex, 9),
    (crest_storm, 5),
    (crest_kaleido, 5),
    (crest_bars, 3),
)


def plan(count: int) -> list[tuple]:
    """Порядок семейств вперемешку: (функция, её собственный номер)."""
    jobs = []
    for family, share in FAMILIES:
        jobs += [(family, n) for n in range(share * count // 256 or 1)]
    while len(jobs) < count:
        jobs.append((crest_icon, len(jobs)))
    jobs = jobs[:count]
    return sorted(jobs, key=lambda job: hashlib.sha256(f"l2hub-crest-order-{job[0].__name__}-{job[1]}".encode()).digest())


def grit(rows: list, seed: int, depth: float) -> list:
    """Неровный свет: у настоящих эмблем картинка уменьшена и дрожит, идеальная заливка выдаёт генератор."""
    return [[tuple(min(255, int(c * (1 - depth + depth * noise(seed, x / 1.7, y / 1.7) * 1.3))) for c in color) for x, color in enumerate(row)] for y, row in enumerate(rows)]


def vivid(rows: list) -> bool:
    """Эмблема видна в окне клана: есть яркие пиксели и есть контраст. Иначе — другой бросок."""
    lumas = [0.3 * r + 0.59 * g + 0.11 * b for row in rows for r, g, b in row]
    return max(lumas) - min(lumas) >= 110 and sum(1 for value in lumas if value >= 75) >= 14


def render(job: tuple, salt: int, w: int, h: int) -> list[list[tuple]]:
    family, number = job
    dice = Dice("l2hub-clan-crest-living", family.__name__, number, salt)
    rows = family(dice, number, w, h)
    if dice.chance(30):
        rows = grit(rows, dice.below(100000), 0.28)
    return rows
