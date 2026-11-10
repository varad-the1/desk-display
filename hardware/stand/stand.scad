// Desk Display stand: Lenovo Tab M10 FHD REL (TB-X605L/LC), landscape, Echo-Show style lean.
// One piece, prints with NO supports, flat on the bed. Open truss design = least possible filament.
//
// Tablet: 242 x 168 x 8.1 mm (Lenovo spec). If the tablet is in a case, raise 'case' by the extra thickness.
// Everything is a parameter. Set preview_ghosts=true to see the tablet and a speaker in the model.

$fn = 24;

// ---------- tablet ----------
TW = 242;        // width in landscape
TH = 168;        // height in landscape
TT = 8.1;        // thickness
case = 0;        // extra thickness if it wears a case (mm)
clear = 0.6;     // slack in the bottom lip

// ---------- stand ----------
lean   = 22;     // degrees back from vertical (Echo Show 8 is about 20-25)
zb     = 3;      // rail / platform thickness
rail_y = 57;     // rails sit under the tablet at +-57 mm from the centre
rail_w = 8;      // rail width
post_t = 3.5;     // post thickness
post_w = 8; 
strut_t = 3;  
strut_w = 7;
post_len = 112;  // how far up the tablet back the posts reach (tablet is 168 tall)
bar_h  = 13;     // height of the wedge that joins the posts and touches the tablet back
bar_d  = 5;      // depth of that wedge
lip_h  = 15;     // front lip height measured along the tablet
lip_t  = 3.5;

// ---------- speaker platform (behind the stand) ----------
x_d   = 90;      // where the platform starts (the strut lands here)
pd    = 90;      // platform depth. Speaker footprint should fit in rail_y*2-rail_w wide x this
rear_lip = 6;    // little wall at the very back so the speaker cannot slide off

// ---------- cable clips ----------
clip_id  = 5.4;  // fits a 3.5 mm AUX cable and a normal USB cable
clip_wall = 1.6;
clip_len = 8;
clip_slit = 3.4; // slit is narrower than the cable so it snaps in
clip_x = [14, 70, 150];

preview_ghosts = false;

TTT = TT + case;
th = lean;
x_end = x_d + pd;

function Lp(x, z) = [x * cos(th) + z * sin(th), zb - x * sin(th) + z * cos(th)];  // tablet-frame -> world (x, z)

module L() { translate([0, 0, zb]) rotate([0, th, 0]) children(); }

// extrude a 2D shape that lives in the x/z plane, at world y, width w
module slab(y, w) { translate([0, y, 0]) rotate([90, 0, 0]) linear_extrude(height = w, center = true) children(); }

module member(p1, p2, t, y, w) {
    slab(y, w) hull() { translate(p1) circle(d = t, $fn = 16); translate(p2) circle(d = t, $fn = 16); }
}

module clip(y, x, side) {
    // C-shaped ring around the x axis, opening upwards, sitting outside the rail
    od = clip_id + 2 * clip_wall;
    translate([x, y + side * (rail_w / 2 + od / 2 - 0.4), od / 2])
    rotate([0, 90, 0]) translate([0, 0, -clip_len / 2])
    difference() {
        union() {
            cylinder(d = od, h = clip_len);
            // flat foot so it sticks to the bed (local +x = down)
            translate([0, -od / 3, 0]) cube([od / 2, 2 * od / 3, clip_len]);
        }
        translate([0, 0, -1]) cylinder(d = clip_id, h = clip_len + 2);
        // slit pointing up (world +z = local -x after rotate)
        translate([-od, -clip_slit / 2, -1]) cube([od, clip_slit, clip_len + 2]);
    }
}

module stand() {
    for (s = [-1, 1]) {
        y = s * rail_y;
        // rail: from in front of the lip to the back of the platform
        slab(y, rail_w) translate([-17, 0]) square([x_end + 17, zb]);

        // post: leans with the tablet, behind it
        L() translate([0, y - post_w / 2, 0]) cube([post_t, post_w, post_len]);

        // lip: the tablet's bottom edge sits against it
        L() translate([-TTT - clear - lip_t, y - rail_w / 2, -10]) cube([lip_t, rail_w, 10 + lip_h]);

        // strut: top of the post down to the platform front
        p1 = Lp(post_t / 2, post_len - 8);
        member(p1, [x_d, zb / 2], strut_t, y, strut_w);
    }

    // wedge across the top of the posts: the tablet leans on its flat face; steep underside prints without supports
    L() translate([0, rail_y + post_w / 2, 0]) rotate([90, 0, 0])
        linear_extrude(height = 2 * rail_y + post_w)
            polygon([[0, post_len - bar_h], [0, post_len], [bar_d, post_len]]);

    // platform cross bars
    slab_bar = 8;
    translate([x_d - 5, -rail_y - rail_w / 2, 0]) cube([10, 2 * rail_y + rail_w, zb]);
    translate([x_end - slab_bar, -rail_y - rail_w / 2, 0]) cube([slab_bar, 2 * rail_y + rail_w, zb]);
    // little lip at the back
    translate([x_end - 2, -rail_y - rail_w / 2, 0]) cube([2, 2 * rail_y + rail_w, zb + rear_lip]);

    // cable clips on both outer sides
    for (s = [-1, 1]) for (cx = clip_x) clip(s * rail_y, cx, s);
}

intersection() {
    stand();
    translate([-100, -300, 0]) cube([600, 600, 400]);   // nothing below the bed
}

if (preview_ghosts) {
    %L() translate([-TT, -TW / 2, 0]) cube([TT, TW, TH]);                     // tablet
    %translate([x_d + 10, -45, zb]) cube([pd - 20, 90, 60]);                  // speaker block (example)
}
